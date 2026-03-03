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
import com.google.firebase.messaging.Notification
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

// Track which deviceIds already have listeners so we don't double-register
private val activeDeviceListeners = ConcurrentHashMap<String, Boolean>()

/**
 * Reads all users from Firebase, then for each user that has a deviceId + fcmToken,
 * registers sensor listeners scoped to that specific device.
 * Also watches for new users / token updates dynamically.
 */
fun startDynamicListeners() {
    val database = FirebaseDatabase.getInstance().reference

    // Listen for scheme updates — sent to all users dynamically below
    database.child("schemes/latest").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val schemeTitle = snapshot.getValue(String::class.java) ?: return
            // Send scheme notification to every registered user's fcmToken
            database.child("users").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(usersSnapshot: DataSnapshot) {
                    for (userSnap in usersSnapshot.children) {
                        val token = userSnap.child("fcmToken").getValue(String::class.java) ?: continue
                        sendNotificationToToken(token, "🌾 New Farmer Scheme", "A new scheme was announced: $schemeTitle")
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
        override fun onCancelled(error: DatabaseError) { println("Scheme listener error: ${error.message}") }
    })

    // Watch the users node — registers device listeners for each user
    database.child("users").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            for (userSnap in snapshot.children) {
                val deviceId = userSnap.child("deviceId").getValue(String::class.java) ?: continue
                val fcmToken = userSnap.child("fcmToken").getValue(String::class.java) ?: continue

                // Only register once per deviceId to avoid duplicate listeners
                if (activeDeviceListeners.putIfAbsent(deviceId, true) == null) {
                    println("Registering listeners for device: $deviceId")
                    registerDeviceListeners(database, deviceId, fcmToken)
                }
            }
        }
        override fun onCancelled(error: DatabaseError) { println("Users listener error: ${error.message}") }
    })
}

/**
 * Registers moisture, temperature, and animal detection listeners for a specific device.
 * Notifications are sent directly to the user's FCM token.
 */
fun registerDeviceListeners(
    database: com.google.firebase.database.DatabaseReference,
    deviceId: String,
    fcmToken: String
) {
    // 1. Low Moisture
    database.child("devices/$deviceId/state/moistPct").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val moisture = snapshot.getValue(Long::class.java) ?: return
            if (moisture < 20) {
                sendNotificationToToken(fcmToken, "🚨 Low Moisture", "Soil moisture on $deviceId dropped to $moisture%!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Moisture listener error: ${error.message}") }
    })

    // 2. High Temperature
    database.child("devices/$deviceId/state/tempC").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val tempStr = snapshot.value?.toString() ?: return
            val temp = tempStr.toFloatOrNull() ?: return
            if (temp > 35.0f) {
                sendNotificationToToken(fcmToken, "⚠️ High Temperature", "Greenhouse temp on $deviceId is $temp°C!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Temp listener error: ${error.message}") }
    })

    // 3. Animal Detection (only alert on false → true transition)
    var previousAnimalState = false
    database.child("devices/$deviceId/state/animalDetected").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val currentAnimalState = snapshot.getValue(Boolean::class.java) ?: return
            if (!previousAnimalState && currentAnimalState) {
                sendNotificationToToken(fcmToken, "🐗 Animal Intrusion!", "Motion detected on $deviceId. Check cameras!")
            }
            previousAnimalState = currentAnimalState
        }
        override fun onCancelled(error: DatabaseError) { println("[$deviceId] Animal listener error: ${error.message}") }
    })
}

/**
 * Sends a push notification directly to a specific device's FCM token.
 * This ensures only the user whose device triggered the alert receives it.
 */
fun sendNotificationToToken(fcmToken: String, title: String, body: String) {
    try {
        val androidConfig = com.google.firebase.messaging.AndroidConfig.builder()
            .setPriority(com.google.firebase.messaging.AndroidConfig.Priority.HIGH)
            .build()

        val message = Message.builder()
            .setAndroidConfig(androidConfig)
            .putData("title", title)
            .putData("body", body)
            .setToken(fcmToken)  // ← Direct to this specific user, not a topic!
            .build()

        val response = FirebaseMessaging.getInstance().send(message)
        println("Successfully sent notification to token: $response")
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
