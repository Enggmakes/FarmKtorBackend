package com.farmbackend

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

fun main() {
    println("Initializing Firebase Admin SDK...")

    try {
        val serviceAccountStream = if (System.getenv("FIREBASE_SERVICE_ACCOUNT") != null) {
            java.io.ByteArrayInputStream(System.getenv("FIREBASE_SERVICE_ACCOUNT").toByteArray())
        } else {
            FileInputStream("serviceAccountKey.json")
        }

        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
            .setDatabaseUrl("https://farm-controlling-cb834-default-rtdb.firebaseio.com")
            .build()

        FirebaseApp.initializeApp(options)
        println("Firebase initialized successfully!")
    } catch (e: Exception) {
        println("Failed to initialize Firebase! Ensure serviceAccountKey.json exists.")
        e.printStackTrace()
        return
    }

    startDynamicListeners()

    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * Maps each deviceId → Set of all FCM tokens of users linked to that device.
 * Multiple users can share the same ESP32 — all of them get notified.
 */
private val deviceTokens = ConcurrentHashMap<String, MutableSet<String>>()

/**
 * Tracks which deviceIds already have Firebase listeners registered to avoid duplicates.
 */
private val activeDeviceListeners = ConcurrentHashMap<String, Boolean>()

fun startDynamicListeners() {
    val database = FirebaseDatabase.getInstance().reference

    // Scheme updates → notify ALL registered users
    database.child("schemes/latest").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val schemeTitle = snapshot.getValue(String::class.java) ?: return
            val allTokens = deviceTokens.values.flatten().toSet()
            allTokens.forEach { token ->
                sendNotificationToToken(token, "🌾 New Farmer Scheme", "A new scheme was announced: $schemeTitle")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("Scheme listener error: ${error.message}") }
    })

    // Watch all users — build deviceId → tokens map, register listeners for new devices
    database.child("users").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            for (userSnap in snapshot.children) {
                val deviceId = userSnap.child("deviceId").getValue(String::class.java) ?: continue
                val fcmToken = userSnap.child("fcmToken").getValue(String::class.java) ?: continue

                // Add token to the set for this device (handles multiple users per device)
                val tokens = deviceTokens.getOrPut(deviceId) { ConcurrentHashMap.newKeySet() }
                tokens.add(fcmToken)

                // Register Firebase listeners only once per deviceId
                if (activeDeviceListeners.putIfAbsent(deviceId, true) == null) {
                    println("Registering listeners for device: $deviceId (currently ${tokens.size} user(s))")
                    registerDeviceListeners(database, deviceId)
                } else {
                    println("Added token to existing device: $deviceId (now ${tokens.size} user(s))")
                }
            }
        }
        override fun onCancelled(error: DatabaseError) { println("Users listener error: ${error.message}") }
    })
}

/**
 * Registers sensor listeners for a specific device.
 * Sends notifications to ALL users linked to this device via deviceTokens map.
 */
fun registerDeviceListeners(
    database: com.google.firebase.database.DatabaseReference,
    deviceId: String
) {
    // 1. Low Moisture
    database.child("devices/$deviceId/state/moistPct").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val moisture = snapshot.getValue(Long::class.java) ?: return
            if (moisture < 20) {
                notifyAllUsersForDevice(deviceId, "🚨 Low Moisture", "Soil moisture on $deviceId dropped to $moisture%!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Moisture error: ${error.message}") }
    })

    // 2. High Temperature
    database.child("devices/$deviceId/state/tempC").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val tempStr = snapshot.value?.toString() ?: return
            val temp = tempStr.toFloatOrNull() ?: return
            if (temp > 35.0f) {
                notifyAllUsersForDevice(deviceId, "⚠️ High Temperature", "Greenhouse temp on $deviceId is $temp°C!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Temp error: ${error.message}") }
    })

    // 3. Humidity Alerts
    database.child("devices/$deviceId/state/humidity").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val humidityStr = snapshot.value?.toString() ?: return
            val humidity = humidityStr.toFloatOrNull() ?: return
            if (humidity < 30.0f) {
                notifyAllUsersForDevice(deviceId, "🚨 Low Humidity", "Greenhouse on $deviceId is too dry ($humidity%)!")
            } else if (humidity > 80.0f) {
                notifyAllUsersForDevice(deviceId, "⚠️ High Humidity", "High saturation on $deviceId: $humidity%!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Humidity error: ${error.message}") }
    })

    // 4. Animal Detection (only on false → true transition)
    var previousAnimalState = false
    database.child("devices/$deviceId/state/animalDetected").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val current = snapshot.getValue(Boolean::class.java) ?: return
            if (!previousAnimalState && current) {
                notifyAllUsersForDevice(deviceId, "🐗 Animal Intrusion!", "Motion detected on $deviceId. Check cameras!", "animal")
            }
            previousAnimalState = current
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Animal error: ${error.message}") }
    })
}

/**
 * Sends a notification to every user linked to the given device.
 */
fun notifyAllUsersForDevice(deviceId: String, title: String, body: String, soundType: String? = null) {
    val tokens = deviceTokens[deviceId] ?: return
    println("Notifying ${tokens.size} user(s) for device: $deviceId")
    tokens.forEach { token -> sendNotificationToToken(token, title, body, soundType) }
}

fun sendNotificationToToken(fcmToken: String, title: String, body: String, soundType: String? = null) {
    try {
        val androidConfig = com.google.firebase.messaging.AndroidConfig.builder()
            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
            .build()

        val messageBuilder = Message.builder()
            .setAndroidConfig(androidConfig)
            .putData("title", title)
            .putData("body", body)
            .setToken(fcmToken)

        if (soundType != null) {
            messageBuilder.putData("sound_type", soundType)
        }

        val message = messageBuilder.build()

        val response = FirebaseMessaging.getInstance().send(message)
        println("Sent notification: $response")
    } catch (e: Exception) {
        println("Error sending notification to token:")
        e.printStackTrace()
    }
}

fun Application.module() {
    routing {
        get("/") {
            call.respondText("I am awake!")
        }
    }
}
