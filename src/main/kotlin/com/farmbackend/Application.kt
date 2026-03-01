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
import java.io.FileInputStream

fun main() {
    println("Initializing Firebase Admin SDK...")
    
    // 🔥 Replace "serviceAccountKey.json" with the path to your actual Firebase Admin key!
    // You download this from Firebase Console -> Project Settings -> Service Accounts -> "Generate new private key"
    try {
        val serviceAccountStream = if (System.getenv("FIREBASE_SERVICE_ACCOUNT") != null) {
            java.io.ByteArrayInputStream(System.getenv("FIREBASE_SERVICE_ACCOUNT").toByteArray())
        } else {
            FileInputStream("serviceAccountKey.json")
        }

        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAccountStream))
            .setDatabaseUrl("https://farm-controlling-cb834-default-rtdb.firebaseio.com") // Replace with your FIREBASE DB URL
            .build()

        FirebaseApp.initializeApp(options)
        println("Firebase initialized successfully!")
    } catch (e: Exception) {
        println("Failed to initialize Firebase! Ensure serviceAccountKey.json exists.")
        e.printStackTrace()
        return
    }

    startListeners()

    // Start a basic Ktor server just to keep the process alive
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun startListeners() {
    val database = FirebaseDatabase.getInstance().reference

    // 1. Listen for Low Moisture
    database.child("devices/farm-esp32-01/state/moistPct").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val moisture = snapshot.getValue(Long::class.java) ?: return
            if (moisture < 20) {
                sendNotification("farm_alerts", "🚨 Low Moisture", "Soil moisture dropped to $moisture%!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("Moisture listener error: ${error.message}") }
    })

    // 2. Listen for High Temp
    database.child("devices/farm-esp32-01/state/tempC").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val tempStr = snapshot.value?.toString() ?: return
            val temp = tempStr.toFloatOrNull() ?: return
            if (temp > 35.0f) {
                sendNotification("farm_alerts", "⚠️ High Temperature", "Greenhouse temp is $temp°C!")
            }
        }
        override fun onCancelled(error: DatabaseError) { println("Temp listener error: ${error.message}") }
    })

    // 3. Listen for Animal Detection
    var previousAnimalState = false
    database.child("devices/farm-esp32-01/state/animalDetected").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            val currentAnimalState = snapshot.getValue(Boolean::class.java) ?: return
            
            // Only alert when changing from false -> true
            if (!previousAnimalState && currentAnimalState) {
                sendNotification("farm_alerts", "🐗 Animal Intrusion!", "Motion detected on the farm. Check cameras!")
            }
            previousAnimalState = currentAnimalState
        }
        override fun onCancelled(error: DatabaseError) { println("Animal listener error: ${error.message}") }
    })

    // 4. Listen for New Farmer Schemes
    database.child("schemes/latest").addValueEventListener(object : ValueEventListener {
        override fun onDataChange(snapshot: DataSnapshot) {
            // Assume the snapshot contains the title of the scheme
            val schemeTitle = snapshot.getValue(String::class.java) ?: return
            sendNotification("scheme_updates", "🌾 New Farmer Scheme", "A new scheme was announced: $schemeTitle")
        }
        override fun onCancelled(error: DatabaseError) { println("Scheme listener error: ${error.message}") }
    })
}

fun sendNotification(topic: String, title: String, body: String) {
    try {
        val message = Message.builder()
            .putData("title", title)
            .putData("body", body)
            .setTopic(topic)
            .build()

        val response = FirebaseMessaging.getInstance().send(message)
        println("Successfully sent message to $topic: $response")
    } catch (e: Exception) {
        println("Error sending notification to $topic:")
        e.printStackTrace()
    }
}

fun Application.module() {
    // Basic Ktor module space, no HTTP routes strictly required since we only use Firebase listeners!
}
