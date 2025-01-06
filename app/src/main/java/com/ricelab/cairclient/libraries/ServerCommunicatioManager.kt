package com.ricelab.cairclient.libraries

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.ricelab.cairclient.DueIntervention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.net.ssl.*

private const val TAG = "ServerCommunicationManager"

data class FirstServerResponse(
    val firstSentence: String,
    val dialogueState: Map<String, Any>
)

class ServerCommunicationManager(
    private val context: Context,
    private val serverIp: String,
    private val serverPort: Int,
    private val openAIApiKey: String
) {

    private val client: OkHttpClient
    private val gson = Gson()

    init {
        client = createSecureOkHttpClient()
    }

    // Create an OkHttpClient that trusts the server certificate
    private fun createSecureOkHttpClient(): OkHttpClient {
        return try {
            // Convert server IP to filename format (replace dots with underscores)
            val ipFormatted = serverIp.replace(".", "_")
            val certificateFileName = "certificates/server_$ipFormatted.crt"

            Log.i("MainActivity", "Certificate name: $certificateFileName")
            // Load the certificate from assets
            val certificateFactory = CertificateFactory.getInstance("X.509")
            val caInput: InputStream = context.assets.open(certificateFileName)
            val ca = certificateFactory.generateCertificate(caInput)
            caInput.close()

            // Create a KeyStore containing our trusted CAs
            val keyStoreType = KeyStore.getDefaultType()
            val keyStore = KeyStore.getInstance(keyStoreType).apply {
                load(null, null)
                setCertificateEntry("ca", ca)
            }

            // Create a TrustManager that trusts the CAs in our KeyStore
            val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }

            val trustManagers = trustManagerFactory.trustManagers
            val x509TrustManager = trustManagers[0] as X509TrustManager

            // Create an SSLContext that uses our TrustManager
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(x509TrustManager), null)
            }

            // Create an SSLSocketFactory with our SSLContext
            val sslSocketFactory = sslContext.socketFactory

            // Build the OkHttpClient with the custom SSL settings
            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, x509TrustManager)
                .hostnameVerifier { hostname, session ->
                    // Implement hostname verification logic if necessary
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                }
                .build()
        } catch (e: Exception) {
            throw RuntimeException("Failed to create a secure OkHttpClient: ${e.message}", e)
        }
    }

    suspend fun firstServerRequest(language: String): FirstServerResponse {
        val url = "https://$serverIp:$serverPort/CAIR_hub/start"

        Log.d(TAG,"Url for server connect: $url")

        // Preparare il payload della richiesta come stringa JSON
        val jsonPayloadString = gson.toJson(
            mapOf(
                "language" to language,
                "openai_api_key" to openAIApiKey
            )
        )

        // Costruire la richiesta POST con payload JSON
        val requestBody = jsonPayloadString.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .build()

        return withContext(Dispatchers.IO) {
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    // Leggere il corpo della risposta come stringa
                    val responseBody = response.body?.string() ?: throw Exception("Empty response")
                    // Analizzare la risposta JSON
                    parseFirstServerResponse(responseBody)
                } else {
                    throw Exception("Server returned an error: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e("ServerCommunication", "Failed to acquire initial state: ${e.message}")
                throw e // Rilanciare l'eccezione per gestirla altrove
            }
        }
    }

    private fun parseFirstServerResponse(responseBody: String): FirstServerResponse {
        return try {
            // Analizzare la risposta JSON in una mappa
            val responseData = gson.fromJson(responseBody, Map::class.java) as Map<*, *>
            Log.d(TAG, "First server response dialogue_state = ${responseData["dialogue_state"]}")
            val firstSentence = responseData["first_sentence"] as? String
                ?: throw Exception("Invalid first_sentence format")
            val dialogueState = (responseData["dialogue_state"] as? Map<*, *>)?.mapKeys { it.key.toString() }
                ?.mapValues { it.value ?: throw Exception("Null value found in dialogue_state") }
                ?: throw Exception("Invalid dialogue_state format")
            Log.i(TAG, "Received dialogue_state: $dialogueState")
            FirstServerResponse(firstSentence = firstSentence, dialogueState = dialogueState)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing JSON response: ${e.message}")
            throw Exception("Error parsing JSON response: ${e.message}", e)
        }
    }

    suspend fun hubRequest(
        requestType: String,
        xmlString: String,
        language: String,
        conversationState: ConversationState,
        denseCapResult: List<String>,
        dueIntervention: DueIntervention
    ): ConversationState? {

        val url = "https://$serverIp:$serverPort/CAIR_hub"
        Log.d(TAG, "Performing request to: $url")

        // Convert DueIntervention to a map
        val dueInterventionMap = mapOf(
            "type" to dueIntervention.type,
            "sentence" to dueIntervention.sentence,
            "exclusive" to dueIntervention.exclusive
        )

        // Compose the data payload to be sent
        val data = mapOf(
            "req_type" to requestType,
            "openai_api_key" to openAIApiKey,
            "client_sentence" to xmlString,
            "language" to language,
            "due_intervention" to dueInterventionMap,
            "dialogue_state" to conversationState.dialogueState.toMap(),
            "dialogue_statistics" to conversationState.dialogueStatistics.toMap(),
            "speakers_info" to conversationState.speakersInfo.toMap(),
            "prev_speaker_info" to mapOf(
                "id" to conversationState.prevTurnLastSpeaker,
                "topic" to conversationState.prevSpeakerTopic
            ),
            "dense_cap_result" to denseCapResult
        )

        Log.d(TAG, "xmlString = $xmlString")
        Log.d(TAG, "client_sentence = ${data["client_sentence"]}")
        val jsonData = JSONObject(data).toString().toByteArray(Charsets.UTF_8)
        val jsonstring = JSONObject(data).toString()
        Log.d(TAG, "jsonData = $jsonstring")

        val compressedData = compressData(jsonData)

        // Create a POST request
        val requestBody = compressedData.toRequestBody("application/octet-stream".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        val startTime = System.currentTimeMillis()

        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Performing $requestType request...")
                val response = client.newCall(request).execute()
                val endTime = System.currentTimeMillis()

                if (!response.isSuccessful) {
                    // The server returned a non-200 code
                    val errorCode = response.code
                    Log.e(TAG, "Server returned an error: $errorCode")
                    throw Exception("Server error with code: $errorCode")
                }

                // Response was successful, parse it
                val responseBodyBytes = response.body?.bytes()
                    ?: throw Exception("Response body is null or empty")

                // Decompress data
                val decompressedData = try {
                    decompressData(responseBodyBytes)
                } catch (e: Exception) {
                    Log.e(TAG, "Decompression error: ${e.message}")
                    throw Exception("Failed to decompress data", e)
                }

                // Convert to string
                val responseBodyString = String(decompressedData, Charsets.UTF_8)
                Log.i(TAG, "Received JSON string: $responseBodyString")

                // Parse JSON
                val jsonResponse = try {
                    JSONObject(responseBodyString)
                } catch (e: Exception) {
                    Log.e(TAG, "Invalid JSON format: ${e.message}")
                    throw Exception("Failed to parse JSON", e)
                }

                Log.i(TAG, "Received JSON: $jsonResponse")
                val error = jsonResponse.optString("error", "")
                if (error.isNotEmpty()) {
                    // The Hub sent back an explicit error
                    Log.e(TAG, "Error from Hub: $error")
                    return@withContext null
                }

                // Update the conversation state
                try {
                    conversationState.dialogueState.updateFromJson(
                        jsonResponse.getJSONObject("dialogue_state")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update dialogue state: ${e.message}")
                    throw Exception("Failed to update dialogue state", e)
                }

                try {
                    conversationState.dialogueStatistics.updateFromJson(
                        jsonResponse.getJSONObject("dialogue_statistics")
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update dialogue statistics: ${e.message}")
                    throw Exception("Failed to update dialogue statistics", e)
                }

                val updatedPlanSentence = jsonResponse.optString("plan_sentence", conversationState.planSentence ?: "")
                val updatedPlan = jsonResponse.optString("plan", conversationState.plan ?: "")

                Log.i(TAG, "$requestType request response time: ${endTime - startTime}ms")

                // Return the updated conversation state
                return@withContext conversationState.copy(
                    planSentence = updatedPlanSentence,
                    plan = updatedPlan
                )
            } catch (e: Exception) {
                // Detailed logs for exceptions
                Log.e(TAG, "Failed to acquire updated state during $requestType. Exception: ${e.message}")
                Log.e(TAG, "Stack trace:\n${Log.getStackTraceString(e)}")
                e.printStackTrace()
                null  // Return null if there are errors
            }
        }
    }

    // Compress the data using zlib
    private fun compressData(data: ByteArray): ByteArray {
        val deflater = Deflater()
        val compressedData = ByteArray(data.size)
        deflater.setInput(data)
        deflater.finish()
        val compressedLength = deflater.deflate(compressedData)
        deflater.end()
        return compressedData.copyOf(compressedLength)
    }

    private fun decompressData(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val outputStream = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                outputStream.write(buffer, 0, count)
            }
        } catch (e: DataFormatException) {
            inflater.end()
            throw RuntimeException("Failed to decompress data: ${e.message}", e)
        }
        inflater.end()
        return outputStream.toByteArray()
    }
}