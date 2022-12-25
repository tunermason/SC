package com.mctw.sc.call

import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection.*
import org.webrtc.RtpReceiver

internal open class DefaultObserver : Observer {
    override fun onSignalingChange(signalingState: SignalingState) {}
    override fun onIceConnectionChange(iceConnectionState: IceConnectionState) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(iceGatheringState: IceGatheringState) {}
    override fun onIceCandidate(iceCandidate: IceCandidate) {}
    override fun onIceCandidatesRemoved(iceCandidates: Array<IceCandidate>) {}
    override fun onAddStream(mediaStream: MediaStream) {}
    override fun onRemoveStream(mediaStream: MediaStream) {}
    override fun onDataChannel(dataChannel: DataChannel) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
}