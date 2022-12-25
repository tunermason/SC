package com.mctw.sc.call

import android.content.Context
import android.content.Intent
import androidx.lifecycle.Lifecycle
import com.mctw.sc.*
import org.json.JSONException
import org.json.JSONObject
import org.libsodium.jni.Sodium
import org.webrtc.*
import org.webrtc.PeerConnection.*
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class RTCCall : DataChannel.Observer {
    var state = CallState.WAITING
    var commSocket: Socket?
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    private var offer: String? = null

    private var remoteVideoSink: ProxyVideoSink? = null
    private var localVideoSink: ProxyVideoSink? = null

    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null

    private var contact: Contact
    private var callActivity: CallContext? = null
    private lateinit var eglBase: EglBase
    private var binder: MainService.MainBinder
    private var statsTimer = Timer()
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val executor = Executors.newSingleThreadExecutor()

    private val audioConstraints = MediaConstraints()
    private val sdpMediaConstraints = MediaConstraints()

    private var isCameraEnabled = false
    private var isMicrophoneEnabled = false
    private var useFrontFacingCamera = false

    fun getMicrophoneEnabled(): Boolean {
        return isMicrophoneEnabled
    }

    fun getCameraEnabled(): Boolean {
        return isCameraEnabled
    }

    fun setCameraEnabled(enabled: Boolean) {
        Log.d(this, "setCameraEnabled")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "setCameraEnabled() executor start")
            if (videoCapturer == null) {
                Log.w(this, "setCameraEnabled no ready to be called => ignore")
                return@execute
            }

            if (this.isCameraEnabled == enabled) {
                Log.w(this, "setCameraEnabled already $enabled => ignore")
                return@execute
            }

            if (dataChannel == null) {
                Log.w(this, "setCameraEnabled dataChannel not set => ignore")
                return@execute
            }

            if (dataChannel!!.state() != DataChannel.State.OPEN) {
                Log.w(this, "setCameraEnabled dataChannel not ready => ignore")
                return@execute
            }

            Log.d(this, "setVideoEnabled: $enabled")
            try {
                // send own camera state over data channel
                val o = JSONObject()
                if (enabled) {
                    o.put(STATE_CHANGE_MESSAGE, CAMERA_ENABLE_MESSAGE)
                } else {
                    o.put(STATE_CHANGE_MESSAGE, CAMERA_DISABLE_MESSAGE)
                }

                if (sendOnDataChannel(o)) {
                    if (enabled) {
                        videoCapturer!!.startCapture(1280, 720, 25)
                        callActivity?.onLocalVideoEnabled(true)
                    } else {
                        callActivity?.onLocalVideoEnabled(false)
                        videoCapturer!!.stopCapture()
                    }

                    this.isCameraEnabled = enabled
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            Log.d(this, "setCameraEnabled() executor end")
        }
    }

    fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        execute {
            if (!getCameraEnabled() || videoCapturer == null || videoSource == null) {
                Log.e(this, "Failed to change capture format. Video: ${getCameraEnabled()}.")
            } else {
                Log.d(this, "changeCaptureFormat: ${width}x${height}@${framerate}")
                videoSource?.adaptOutputFormat(width, height, framerate)
            }
        }
  }

    private fun execute(r: Runnable) {
        try {
            executor.execute(r)
        } catch (e: RejectedExecutionException) {
            // can happen when the executor has shut down
            Log.w(this, "got RejectedExecutionException")
        }
    }

    private fun sendOnDataChannel(obj: JSONObject): Boolean {
        Log.d(this, "sendOnDataChannel")

        val channel = dataChannel
        if (channel == null) {
            Log.w(this, "setCameraEnabled dataChannel not set => ignore")
            return false
        }

        if (channel.state() != DataChannel.State.OPEN) {
            Log.w(this, "setCameraEnabled dataChannel not ready => ignore")
            return false
        }

        try {
            channel.send(
                DataChannel.Buffer(
                    ByteBuffer.wrap(
                        obj.toString().toByteArray()
                    ), false
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }

    // called for incoming calls
    constructor(
        binder: MainService.MainBinder,
        contact: Contact,
        commSocket: Socket,
        offer: String?
    ) {
        Log.d(this, "RTCCall created for incoming calls")

        this.contact = contact
        this.commSocket = commSocket
        this.binder = binder
        this.offer = offer

        createMediaConstraints()
    }

    // called for outgoing calls
    constructor(
        binder: MainService.MainBinder,
        contact: Contact
    ) {
        Log.d(this, "RTCCall created for outgoing calls")

        this.contact = contact
        this.commSocket = null
        this.binder = binder

        createMediaConstraints()
    }

    private fun createMediaConstraints() {
        sdpMediaConstraints.optional.add(MediaConstraints.KeyValuePair("offerToReceiveAudio", "true"))
        sdpMediaConstraints.optional.add(MediaConstraints.KeyValuePair("offerToReceiveVideo", "false"))
        sdpMediaConstraints.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))

        if (binder.getSettings().noAudioProcessing) {
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false"))
        }
    }

    private fun createOutgoingCall(contact: Contact, offer: String) {
        Log.d(this, "createOutgoingCall")
        Thread {
            try {
                createOutgoingCallInternal(contact, offer)
            } catch (e: Exception) {
                e.printStackTrace()
                reportStateChange(CallState.ERROR_OTHER)
            }
        }.start()
    }

    private fun createOutgoingCallInternal(contact: Contact, offer: String) {
        Log.d(this, "createOutgoingCallInternal")

        val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val settings = binder.getSettings()
        val ownPublicKey = settings.publicKey
        val ownSecretKey = settings.secretKey

        val socket = createCommSocket(contact)
        if (socket == null) {
            return
        }

        callActivity?.onRemoteAddressChange(socket.remoteSocketAddress as InetSocketAddress, true)
        commSocket = socket

        val remote_address = socket.remoteSocketAddress as InetSocketAddress

        Log.d(this, "outgoing call from remote address: $remote_address")

        run {
            // remember latest working address
            val workingAddress = InetSocketAddress(remote_address.address, MainService.serverPort)
            val storedContact = binder.getContacts().getContactByPublicKey(contact.publicKey)
            storedContact?.lastWorkingAddress = workingAddress
        }

        val pr = PacketReader(socket)
        reportStateChange(CallState.CONNECTING)
        run {
            Log.d(this, "outgoing call: send call")
            val obj = JSONObject()
            obj.put("action", "call")
            obj.put("offer", offer) // WebRTC offer!
            val encrypted = Crypto.encryptMessage(
                obj.toString(),
                contact.publicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (encrypted == null) {
                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
                return
            }

            val pw = PacketWriter(socket)
            pw.writeMessage(encrypted)
        }
        run {
            Log.d(this, "outgoing call: expect ringing")
            val response = pr.readMessage()
            if (response == null) {
                reportStateChange(CallState.ERROR_OTHER)
                return
            }

            val decrypted = Crypto.decryptMessage(
                response,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (decrypted == null) {
                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
                return
            }

            if (!contact.publicKey.contentEquals(otherPublicKey)) {
                reportStateChange(CallState.ERROR_AUTHENTICATION)
                return
            }

            val obj = JSONObject(decrypted)
            if (obj.optString("action", "") != "ringing") {
                Log.d(this, "action not equals ringing")
                reportStateChange(CallState.ERROR_OTHER)
                return
            }
            reportStateChange(CallState.RINGING)
        }
        run {
            Log.d(this, "outgoing call: expect connected/dismissed")
            val response = pr.readMessage()
            if (response == null) {
                return
            }

            val decrypted = Crypto.decryptMessage(
                response,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (decrypted == null) {
                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
                return
            }

            if (!contact.publicKey.contentEquals(otherPublicKey)) {
                reportStateChange(CallState.ERROR_AUTHENTICATION)
                return
            }

            val obj = JSONObject(decrypted)
            when (val action = obj.getString("action")) {
                "connected" -> {
                    Log.d(this, "outgoing call: connected")
                    reportStateChange(CallState.CONNECTED)
                    handleAnswer(obj.getString("answer"))
                }
                "dismissed" -> {
                    Log.d(this, "outgoing call: dismissed")
                    reportStateChange(CallState.DISMISSED)
                }
                else -> {
                    Log.d(this, "outgoing call: unknown action reply $action")
                    reportStateChange(CallState.ERROR_OTHER)
                }
            }
        }

        continueOnSocket()
    }

    // Continue listening for socket message.
    // Must run on separate thread!
    fun continueOnSocket() {
        val socket = commSocket
        if (socket == null) {
            throw IllegalStateException("commSocket expected not to be null")
        }

        val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
        val settings = binder.getSettings()
        val ownPublicKey = settings.publicKey
        val ownSecretKey = settings.secretKey
        val pr = PacketReader(socket)

        while (!socket.isClosed) {
            val response = pr.readMessage() ?: continue

            val decrypted = Crypto.decryptMessage(
                response,
                otherPublicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (decrypted == null) {
                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
                break
            }

            val obj = JSONObject(decrypted)
            when (val action = obj.getString("action")) {
                "dismissed" -> {
                    Log.d(this, "outgoing call: dismissed")
                    reportStateChange(CallState.DISMISSED)
                }
                "detach" -> {
                    // meant for future versions that do
                    // not need to keep the socket open
                    Log.d(this, "outgoing call: dismissed")
                    return
                }
                else -> {
                    Log.d(this, "outgoing call: unknown action reply $action")
                    reportStateChange(CallState.ERROR_OTHER)
                }
            }
        }

        reportStateChange(CallState.DISMISSED)
    }

    fun initOutgoing() {
        Log.d(this, "initOutgoing")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "initOutgoing() executor start")
            val rtcConfig = RTCConfiguration(emptyList())
            rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN
            rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_ONCE

            peerConnection = factory.createPeerConnection(rtcConfig, object : DefaultObserver() {

                override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                    super.onIceGatheringChange(iceGatheringState)
                    if (iceGatheringState == IceGatheringState.COMPLETE) {
                        Log.d(this, "outgoing call: send offer")
                        createOutgoingCall(contact, peerConnection!!.localDescription.description)
                    }
                }

                override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                    Log.d(this, "onIceConnectionChange ${iceConnectionState.name}")
                    super.onIceConnectionChange(iceConnectionState)
                    if (iceConnectionState == IceConnectionState.DISCONNECTED) {
                        reportStateChange(CallState.ENDED)
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d(this, "onConnectionChange: ${newState.name}")
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    handleMediaStream(mediaStream)
                }

            })!!

            val init = DataChannel.Init()
            init.ordered = true
            dataChannel = peerConnection!!.createDataChannel("data", init)
            dataChannel!!.registerObserver(this)

            callActivity?.onCameraEnabled()

            createPeerConnection()

            peerConnection!!.createOffer(object : DefaultSdpObserver() {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    super.onCreateSuccess(sessionDescription)
                    peerConnection!!.setLocalDescription(DefaultSdpObserver(), sessionDescription)
                }
            }, sdpMediaConstraints)

            Log.d(this, "initOutgoing() executor end")
        }
    }

    private fun createCommSocket(contact: Contact): Socket? {
        Log.d(this, "createCommSocket")

        Utils.checkIsNotOnMainThread()

        val settings = binder.getSettings()
        val useNeighborTable = settings.useNeighborTable
        val connectTimeout = settings.connectTimeout

        var unknownHostException = false
        var connectException = false
        var socketTimeoutException = false
        var exception = false

        for (address in AddressUtils.getAllSocketAddresses(contact, useNeighborTable)) {
            callActivity?.onRemoteAddressChange(address, false)
            Log.d(this, "try address: $address")

            val socket = Socket()

            try {
                socket.connect(address, connectTimeout)
                reportStateChange(CallState.CONNECTING)
                return socket
            } catch (e: SocketTimeoutException) {
                // no connection
                Log.d(this, "socket has thrown SocketTimeoutException")
                socketTimeoutException = true
            } catch (e: ConnectException) {
                // device is online, but does not listen on the given port
                Log.d(this, "socket has thrown ConnectException")
                connectException = true
            } catch (e: UnknownHostException) {
                // hostname did not resolve
                Log.d(this, "socket has thrown UnknownHostException")
                unknownHostException = true
            } catch (e: Exception) {
                Log.d(this, "socket has thrown Exception")
                exception = true
            }

            closeSocket(socket)
        }

        if (connectException) {
            reportStateChange(CallState.ERROR_CONNECT_PORT)
        } else if (unknownHostException) {
            reportStateChange(CallState.ERROR_UNKNOWN_HOST)
        } else if (exception) {
            reportStateChange(CallState.ERROR_OTHER)
        } else if (socketTimeoutException) {
            reportStateChange(CallState.ERROR_NO_CONNECTION)
        } else {
            reportStateChange(CallState.ERROR_NO_ADDRESSES)
        }

        return null
    }

    fun setRemoteRenderer(remoteVideoSink: ProxyVideoSink?) {
        this.remoteVideoSink = remoteVideoSink
    }

    fun setLocalRenderer(localVideoSink: ProxyVideoSink?) {
        this.localVideoSink = localVideoSink
    }

    override fun onBufferedAmountChange(l: Long) {
        // nothing to do
    }

    override fun onStateChange() {
        // nothing to do
    }

    override fun onMessage(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        val s = String(data)
        try {
            Log.d(this, "onMessage: $s")
            val o = JSONObject(s)
            if (o.has(STATE_CHANGE_MESSAGE)) {
                when (o.getString(STATE_CHANGE_MESSAGE)) {
                    CAMERA_ENABLE_MESSAGE -> callActivity?.onRemoteVideoEnabled(true)
                    CAMERA_DISABLE_MESSAGE -> callActivity?.onRemoteVideoEnabled(false)
                    HANGUP_MESSAGE -> reportStateChange(CallState.DISMISSED)
                    else -> {}
                }
            } else {
                Log.d(this, "unknown message: $s")
            }

        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun releaseCamera() {
        Log.d(this, "releaseCamera")
        Utils.checkIsOnMainThread()

        try {
            videoCapturer?.stopCapture()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun handleMediaStream(stream: MediaStream) {
        Log.d(this, "handleMediaStream")

        execute {
            Log.d(this, "handleMediaStream() executor start")
            if (remoteVideoSink == null || stream.videoTracks.size == 0) {
                return@execute
            }
            stream.videoTracks[0].addSink(remoteVideoSink)
            Log.d(this, "handleMediaStream() executor end")
        }
    }

    private fun createPeerConnection() {
        try {
            peerConnection!!.addTrack(createAudioTrack(), listOf("stream1"))
            peerConnection!!.addTrack(createVideoTrack(), listOf("stream1"))
        } catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun getFrontCameraEnabled(): Boolean {
        return useFrontFacingCamera
    }

    fun setFrontCameraEnabled(enabled: Boolean) {
        Log.d(this, "setFrontCameraEnabled: $enabled")
        Utils.checkIsOnMainThread()
        if (videoCapturer != null) {
            if (enabled != useFrontFacingCamera) {
                (videoCapturer as CameraVideoCapturer).switchCamera(null)
                useFrontFacingCamera = enabled
                callActivity?.onFrontFacingCamera(enabled)
            }
        }
    }

    private fun createVideoTrack(): VideoTrack? {
        videoCapturer = null
        val enumerator = Camera1Enumerator()
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) {
                videoCapturer = enumerator.createCapturer(name, null)
                break
            }
        }

        if (videoCapturer != null) {
            val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
            videoCapturer!!.initialize(surfaceTextureHelper, callActivity!!.getContext(), localVideoSource.capturerObserver)

            val localVideoTrack = factory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource)
            localVideoTrack.addSink(localVideoSink)
            localVideoTrack.setEnabled(true)

            videoSource = localVideoSource

            return localVideoTrack
        }

        return null
    }

    private fun createAudioTrack(): AudioTrack? {
        Log.d(this, "createAudioTrack")
        audioSource = factory.createAudioSource(audioConstraints)
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(isMicrophoneEnabled)
        return localAudioTrack
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        Log.d(this, "setMicrophoneEnabled: $enabled")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "setMicrophoneEnabled() executor start")
            isMicrophoneEnabled = enabled
            localAudioTrack?.setEnabled(enabled)
            callActivity?.onMicrophoneEnabled(enabled)
            Log.d(this, "setMicrophoneEnabled() executor end")
        }
    }

    fun initVideo() {
        Log.d(this, "initVideo")
        Utils.checkIsOnMainThread()
        reportStateChange(CallState.WAITING)

        // must be created in Main/GUI Thread!
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(callActivity!!.getContext())
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val encoderFactory: VideoEncoderFactory
        val decoderFactory: VideoDecoderFactory

        Log.d(this, "video acceleration: ${binder.getSettings().videoHardwareAcceleration}")

        if (binder.getSettings().videoHardwareAcceleration) {
            val enableIntelVp8Encoder = true
            val enableH264HighProfile = true
            encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, enableIntelVp8Encoder, enableH264HighProfile)
            decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        } else {
            encoderFactory = SoftwareVideoEncoderFactory()
            decoderFactory = SoftwareVideoDecoderFactory()
        }

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    private fun handleAnswer(remoteDesc: String) {
        execute {
            Log.d(this, "handleAnswer() executor start")
            peerConnection!!.setRemoteDescription(object : DefaultSdpObserver() {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    Log.d(this, "onSetSuccess")
                }

                override fun onSetFailure(s: String) {
                    super.onSetFailure(s)
                    Log.d(this, "onSetFailure: $s")
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, remoteDesc))
            Log.d(this, "handleAnswer() executor end")
        }
    }

    private fun reportStateChange(state: CallState) {
        Log.d(this, "reportStateChange: $state")

        this.state = state
        callActivity?.onStateChange(state)
    }

    fun setStatsCollector(statsCollector: RTCStatsCollectorCallback?) {
        if (statsCollector == null) {
            statsTimer.cancel()
            statsTimer.purge()
        } else {
            statsTimer = Timer()
            statsTimer.schedule(object : TimerTask() {
                override fun run() {
                    execute {
                        Log.d(this, "setStatsCollector() executor start")
                        try {
                            peerConnection!!.getStats(statsCollector)
                        } catch (e: Exception) {
                            Log.e(this, "Cannot schedule statistics timer $e")
                        }
                        Log.d(this, "setStatsCollector() executor end")
                    }
                }
            }, 0L, StatsReportUtil.STATS_INTERVAL_MS)
        }
    }

    fun setEglBase(eglBase: EglBase) {
        this.eglBase = eglBase
    }

    fun setCallContext(activity: CallContext?) {
        this.callActivity = activity
    }

    fun initIncoming() {
        Log.d(this, "initIncoming")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "initIncoming() executor start")
            val remoteAddress = commSocket!!.remoteSocketAddress as InetSocketAddress
            val rtcConfig = RTCConfiguration(emptyList())
            rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN
            rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_ONCE

            peerConnection = factory.createPeerConnection(rtcConfig, object : DefaultObserver() {
                override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {
                    super.onIceGatheringChange(iceGatheringState)

                    if (iceGatheringState == IceGatheringState.COMPLETE) {
                        Log.d(this, "onIceGatheringChange")
                        try {
                            val settings = binder.getSettings()
                            val ownPublicKey = settings.publicKey
                            val ownSecretKey = settings.secretKey
                            val pw = PacketWriter(commSocket!!)
                            val obj = JSONObject()
                            obj.put("action", "connected")
                            obj.put("answer", peerConnection!!.localDescription.description)
                            val encrypted = Crypto.encryptMessage(
                                obj.toString(),
                                contact.publicKey,
                                ownPublicKey,
                                ownSecretKey
                            )
                            if (encrypted != null) {
                                pw.writeMessage(encrypted)
                                callActivity?.onRemoteAddressChange(remoteAddress, true)
                                reportStateChange(CallState.CONNECTED)
                            } else {
                                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
                            }
                            //new Thread(new SpeakerRunnable(commSocket)).start()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            reportStateChange(CallState.ERROR_OTHER)
                        }
                    }
                }

                override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {
                    Log.d(this, "onIceConnectionChange: ${iceConnectionState.name}")
                    super.onIceConnectionChange(iceConnectionState)
                    if (iceConnectionState == IceConnectionState.DISCONNECTED) {
                        reportStateChange(CallState.ENDED)
                    }
                }

                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    Log.d(this, "onConnectionChange: ${newState.name}")
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    Log.d(this, "onAddStream")
                    super.onAddStream(mediaStream)
                    handleMediaStream(mediaStream)
                }

                override fun onDataChannel(dataChannel: DataChannel) {
                    Log.d(this, "onDataChannel")
                    super.onDataChannel(dataChannel)
                    this@RTCCall.dataChannel = dataChannel
                    this@RTCCall.dataChannel!!.registerObserver(this@RTCCall)
                    callActivity?.onCameraEnabled()
                }
            })!!

            createPeerConnection()

            Log.d(this, "setting remote description")
            peerConnection!!.setRemoteDescription(object : DefaultSdpObserver() {
                override fun onSetSuccess() {
                    super.onSetSuccess()
                    Log.d(this, "creating answer...")
                    peerConnection!!.createAnswer(object : DefaultSdpObserver() {
                        override fun onCreateSuccess(sessionDescription: SessionDescription) {
                            Log.d(this, "onCreateSuccess")
                            super.onCreateSuccess(sessionDescription)
                            peerConnection!!.setLocalDescription(
                                DefaultSdpObserver(),
                                sessionDescription
                            )
                        }

                        override fun onCreateFailure(s: String) {
                            super.onCreateFailure(s)
                            Log.d(this, "onCreateFailure: $s")
                        }
                    }, sdpMediaConstraints)
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offer))

            Log.d(this, "initIncoming() executor end")
        }
    }

    // send over data channel
    private fun hangup_internal() {
        Log.d(this, "hangup_internal")

        // send hangup over WebRTC channel
        val o = JSONObject()
        o.put(STATE_CHANGE_MESSAGE, HANGUP_MESSAGE)

        if (sendOnDataChannel(o)) {
            reportStateChange(CallState.DISMISSED)
        } else {
            reportStateChange(CallState.ERROR_OTHER)
        }
    }

    fun hangup() {
        Log.d(this, "hangup")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "hangup() executor start")
            hangup_internal()
            Log.d(this, "hangup() executor end")
        }
    }

    // send over initial socket
    private fun decline_internal() {
        Log.d(this, "decline_internal")
        // send decline over initial socket
        val socket = commSocket
        if (socket != null && !socket.isClosed) {
            val pw = PacketWriter(socket)
            val settings = binder.getSettings()
            val ownPublicKey = settings.publicKey
            val ownSecretKey = settings.secretKey

            val encrypted = Crypto.encryptMessage(
                "{\"action\":\"dismissed\"}",
                contact.publicKey,
                ownPublicKey,
                ownSecretKey
            )

            if (encrypted != null) {
                try {
                    Log.d(this, "write dismissed message to socket")
                    pw.writeMessage(encrypted)
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                reportStateChange(CallState.DISMISSED)
            } else {
                reportStateChange(CallState.ERROR_CRYPTOGRAPHY)
            }
        }
    }

    fun decline() {
        Log.d(this, "decline")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "decline() executor start")
            decline_internal()
            Log.d(this, "decline() executor end")
        }
    }

    fun cleanup() {
        Log.d(this, "cleanup")
        Utils.checkIsOnMainThread()

        execute {
            Log.d(this, "cleanup() executor start")
            setCallContext(null)
            setStatsCollector(null)

            try {
                Log.d(this, "close socket")
                commSocket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                peerConnection?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Log.d(this, "cleanup() executor end")
        }

        // wait for tasks to finish
        executor.shutdown()
        executor.awaitTermination(4L, TimeUnit.SECONDS)
        Log.d(this, "cleanup done")
    }

    enum class CallState {
        WAITING,
        CONNECTING,
        RINGING,
        CONNECTED,
        DISMISSED,
        ENDED,
        ERROR_AUTHENTICATION,
        ERROR_CRYPTOGRAPHY,
        ERROR_CONNECT_PORT,
        ERROR_UNKNOWN_HOST,
        ERROR_OTHER,
        ERROR_NO_CONNECTION,
        ERROR_NO_ADDRESSES
    }

    interface CallContext {
        fun onStateChange(state: CallState)
        fun onLocalVideoEnabled(enabled: Boolean)
        fun onRemoteVideoEnabled(enabled: Boolean)
        fun onFrontFacingCamera(enabled: Boolean)
        fun onMicrophoneEnabled(enabled: Boolean)
        fun onCameraEnabled()
        fun onRemoteAddressChange(address: InetSocketAddress, isConnected: Boolean)

        fun showTextMessage(message: String)
        fun getContext(): Context
    }

    class ProxyVideoSink : VideoSink {
        private var target: VideoSink? = null

        @Synchronized
        override fun onFrame(frame: VideoFrame) {
            val target = this.target

            if (target == null) {
                Log.d(this, "Dropping frame in proxy because target is null.")
            } else {
                target.onFrame(frame)
            }
        }

        @Synchronized
        fun setTarget(target: VideoSink?) {
            this.target = target
        }
    }

    companion object {
        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"

        private const val STATE_CHANGE_MESSAGE = "StateChange"
        private const val CAMERA_DISABLE_MESSAGE = "CameraDisabled"
        private const val CAMERA_ENABLE_MESSAGE = "CameraEnabled"
        private const val HANGUP_MESSAGE = "Hangup"
        private const val AUDIO_TRACK_ID = "audio1"
        private const val VIDEO_TRACK_ID = "video1"

        private fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

        private fun debugPacket(label: String, msg: ByteArray?) {
            if (msg != null) {
                Log.d(this, "$label: ${msg.size}, ${msg.toHex()}")
            } else {
                Log.d(this, "$label: message is null!")
            }
        }

        private fun closeSocket(socket: Socket) {
            try {
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun createIncomingCall(binder: MainService.MainBinder, socket: Socket) {
            Thread {
                try {
                    createIncomingCallInternal(binder, socket)
                } catch (e: Exception) {
                    e.printStackTrace()
                    //decline()
                }
            }.start()
        }

        private fun createIncomingCallInternal(binder: MainService.MainBinder, socket: Socket) {
            Log.d(this, "createIncomingCallInternal")

            val otherPublicKey = ByteArray(Sodium.crypto_sign_publickeybytes())
            val settings = binder.getSettings()
            val ownSecretKey = settings.secretKey
            val ownPublicKey = settings.publicKey

            val decline = {
                Log.d(this, "declining...")

                try {
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"dismissed\"}",
                        otherPublicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted != null) {
                        val pw = PacketWriter(socket)
                        pw.writeMessage(encrypted)
                    }

                    socket.close()
                } catch (e: Exception) {
                    closeSocket(socket)
                }
            }

            val remote_address = socket.remoteSocketAddress as InetSocketAddress
            val pw = PacketWriter(socket)
            val pr = PacketReader(socket)

            Log.d(this, "incoming peerConnection from $remote_address")

            val request = pr.readMessage()
            if (request == null) {
                Log.d(this, "connection closed")
                socket.close()
                return
            }

            //Log.d(this, "request: ${request.toHex()}")

            val decrypted = Crypto.decryptMessage(request, otherPublicKey, ownPublicKey, ownSecretKey)
            if (decrypted == null) {
                Log.d(this, "decryption failed")
                // cause: the caller might use the wrong key
                socket.close()
                return
            }

            Log.d(this, "request: $decrypted")

            var contact = binder.getContacts().getContactByPublicKey(otherPublicKey)
            if (contact == null && binder.getSettings().blockUnknown) {
                Log.d(this, "block unknown contact => decline")
                decline()
                return
            }

            if (contact != null && contact.blocked) {
                Log.d(this, "blocked contact => decline")
                decline()
                return
            }

            if (contact == null) {
                // unknown caller
                contact = Contact("", otherPublicKey.clone(), ArrayList())
            }

            // suspicious change of identity in during peerConnection...
            if (!contact.publicKey.contentEquals(otherPublicKey)) {
                Log.d(this, "suspicious change of key")
                decline()
                return
            }

            run {
                // remember latest working address
                val workingAddress = InetSocketAddress(remote_address.address, MainService.serverPort)
                val storedContact = binder.getContacts().getContactByPublicKey(contact.publicKey)
                storedContact?.lastWorkingAddress = workingAddress
            }

            val obj = JSONObject(decrypted)
            val action = obj.optString("action", "")
            Log.d(this, "action: $action")
            when (action) {
                "call" -> {
                    if (binder.getCurrentCall() != null) {
                        Log.d(this, "call in progress => decline")
                        decline() // TODO: send busy
                        return
                    }

                    // someone calls us
                    val offer = obj.getString("offer")

                    // respond that we accept the call (our phone is ringing)
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"ringing\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted == null) {
                        Log.d(this, "encryption failed")
                        decline()
                        return
                    }

                    debugPacket("send ringing message:", encrypted)
                    pw.writeMessage(encrypted)

                    val currentCall = RTCCall(binder, contact, socket, offer)
                    binder.setCurrentCall(currentCall)
                    try {
                        val activity = MainActivity.instance
                        if (activity != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            Log.d(this, "start incoming call from stored MainActivity")
                            val intent = Intent(activity, CallActivity::class.java)
                            intent.action = "ACTION_INCOMING_CALL"
                            intent.putExtra("EXTRA_CONTACT", contact)
                            activity.startActivity(intent)
                        } else {
                            Log.d(this, "start incoming call from Service")
                            val service = binder.getService()
                            val intent = Intent(service, CallActivity::class.java)
                            intent.action = "ACTION_INCOMING_CALL"
                            intent.putExtra("EXTRA_CONTACT", contact)
                            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            service.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        binder.setCurrentCall(null)
                        currentCall.cleanup()
                        e.printStackTrace()
                    }
                }
                "ping" -> {
                    Log.d(this, "ping...")
                    // someone wants to know if we are online
                    contact.state = Contact.State.ONLINE
                    val encrypted = Crypto.encryptMessage(
                        "{\"action\":\"pong\"}",
                        contact.publicKey,
                        ownPublicKey,
                        ownSecretKey
                    )

                    if (encrypted == null) {
                        Log.d(this, "encryption failed")
                        decline()
                        return
                    }

                    pw.writeMessage(encrypted)
                }
                "status_change" -> {
                    if (obj.optString("status", "") == "offline") {
                        contact.state = Contact.State.ONLINE
                    } else {
                        Log.d(this, "Received unknown status_change: ${obj.getString("status")}")
                    }
                }
            }
        }
    }
}
