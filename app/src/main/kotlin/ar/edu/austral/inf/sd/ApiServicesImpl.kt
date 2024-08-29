package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

@Component
class ApiServicesImpl: RegisterNodeApiService, RelayApiService, PlayApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""
    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = newSalt()
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)

    override fun registerNode(host: String?, port: Int?, name: String?): RegisterResponse {

        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val uuid = UUID.randomUUID().toString()
        val node = RegisterResponse(host!!, port!!, uuid, newSalt())
        nodes.add(node)

        return RegisterResponse(nextNode.nextHost, nextNode.nextPort, uuid, newSalt())
    }
    override fun relayMessage(message: String, signatures: Signatures): Signature {
        val signature = clientSign(message, currentRequest.getPart("message")?.contentType ?: "text/plain")

        if (nextNode != null) {
            val newSignatures = signatures.copy(
                items = signatures.items + signature
            )
            sendRelayMessage(message, signature.contentType ?: "text/plain", nextNode!!, newSignatures)
        } else {
            if (currentMessageWaiting.value == null) throw BadRequestException("No waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (signature.hash == current.originalHash) "Success" else "Failure",
                receivedHash = signature.hash,
                receivedLength = signature.contentLength ?: message.length,
                receivedContentType = signature.contentType ?: "text/plain",
                signatures = signatures.copy(items = signatures.items + signature)
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return signature
    }


    override fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, "", "")
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
        resultReady.await()
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    internal fun registerToServer(registerHost: String, registerPort: Int) {
        val client = WebClient.create("http://$registerHost:$registerPort")
        val registerRequest = RegisterRequest(host = myServerName, port = myServerPort, name = "IEstevo")
        val registerNodeResponse = client.post()
            .uri("/register")
            .bodyValue(registerRequest)
            .retrieve()
            .bodyToMono(RegisterResponse::class.java)
            .block() ?: throw RuntimeException("Error registrando el nodo")

        nextNode = registerNodeResponse
    }

    private fun sendRelayMessage(body: String, contentType: String, relayNode: RegisterResponse, signatures: Signatures) {
        val client = WebClient.create("http://${relayNode.nextHost}:${relayNode.nextPort}")
        val response = client.post()
            .uri("/relay")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(
                BodyInserters.fromMultipartData("message", body)
                    .with("signatures", signatures))
            .retrieve()
            .bodyToMono(Signature::class.java)
            .block() ?: throw RuntimeException("Error enviando el mensaje al siguiente nodo")
    }



    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String):  String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}