package com.example.chatappjava.ui.call;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.chatappjava.R;
import com.example.chatappjava.config.server_config;
import com.example.chatappjava.utils.TokenManager;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.Camera1Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.PeerConnectionFactory.InitializationOptions;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.RendererCommon;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;
import android.view.View;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class VideoCallActivity extends AppCompatActivity {
    private static final String TAG = "VideoCallActivity";
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private String myUid;
    private String callId;   // room id (use this for WS endpoint)
    private String targetUid; // we may keep for REST metadata
    private boolean isCaller = true; // default caller unless specified
    private String groupId; // New field for group calls

    private EglBase eglBase;
    private SurfaceViewRenderer localRenderer;
    private SurfaceViewRenderer remoteRenderer;
    private PeerConnectionFactory peerConnectionFactory;
    private PeerConnection peerConnection;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoCapturer videoCapturer;
    private SurfaceTextureHelper surfaceTextureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;

    private final OkHttpClient httpClient = new OkHttpClient();
    private WebSocket webSocket;
    private WebSocket notifySocket;
    private ImageButton btnEndCall, btnSwitchCamera;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_call);

        localRenderer = findViewById(R.id.localView);
        remoteRenderer = findViewById(R.id.remoteView);
        btnEndCall = findViewById(R.id.btnEndCall);
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera);

        myUid = getIntent().getStringExtra("USER_ID");
        callId = getIntent().getStringExtra("CALL_ID");
        isCaller = getIntent().getBooleanExtra("IS_CALLER", true);
        groupId = getIntent().getStringExtra("GROUP_ID"); // Retrieve group ID

        if (callId == null || (isCaller && myUid == null)) {
            Toast.makeText(this, "Missing call parameters", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // targetUid is optional for REST; derive if callId formatted uidA_uidB
        targetUid = parseTargetUid(callId, myUid);

        if (!hasAllPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        } else {
        startCallSetup();
        }

        btnEndCall.setOnClickListener(v -> {
            if (isCaller) sendCancelCall();
            else sendEndCall();
            endCallCleanup();
            finish();
        });

        btnSwitchCamera.setOnClickListener(v -> {
            if (videoCapturer instanceof CameraVideoCapturer) {
                try {
                    ((CameraVideoCapturer) videoCapturer).switchCamera(null);
                } catch (Exception e) {
                    Log.w(TAG, "switchCamera failed", e);
                }
            }
        });
    }

    private boolean hasAllPermissions() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean ok = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                    break;
                }
            }
            if (ok) startCallSetup();
            else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void startCallSetup() {
        initEglAndFactory();
        createLocalTracks();
        createPeerConnection();
        connectWebSocket();         // join room (callId) on WS
        connectNotifySocket();      // listen for out-of-band call events (rejected/canceled)
        if (isCaller) {
            createOfferAndSend();   // caller creates and sends offer
        } else {
            // callee tries to fetch stored offer if it was sent before WS connected
            fetchCallInfoAndMaybeHandleOffer();
        }
        // callee otherwise waits for remote offer via WS and then creates answer in handleRemoteOffer
    }

    private void fetchCallInfoAndMaybeHandleOffer() {
        String token = TokenManager.get(this);
        if (token == null) return;
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/call/info/" + callId)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { Log.w(TAG, "fetch call info failed", e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try {
                    String body = response.body() != null ? response.body().string() : null;
                    if (!response.isSuccessful() || body == null) return;
                    JSONObject obj = new JSONObject(body);
                    if (obj.has("offer")) {
                        JSONObject offer = obj.getJSONObject("offer");
                        runOnUiThread(() -> {
                            try { handleRemoteOffer(offer); } catch (JSONException e) { Log.w(TAG, "offer parse", e); }
                        });
                    }
                } catch (Exception ignored) {
                } finally {
                    response.close();
                }
            }
        });
    }

    private void initEglAndFactory() {
        eglBase = EglBase.create();
        Log.d(TAG, "EglBase created: " + (eglBase != null));
        remoteRenderer.init(eglBase.getEglBaseContext(), null);
        localRenderer.init(eglBase.getEglBaseContext(), null);
        Log.d(TAG, "Renderers initialized with EglBase context.");

        // Optimize rendering and scaling, ensuring these are set right after init
        localRenderer.setEnableHardwareScaler(true);
        remoteRenderer.setEnableHardwareScaler(true);
        localRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
        Log.d(TAG, "Renderers scaling type set to SCALE_ASPECT_FIT and hardware scaler enabled.");

        // Ensure local preview overlays the remote SurfaceView
        localRenderer.setZOrderMediaOverlay(true);
        localRenderer.setZOrderOnTop(true); // Ensure local is always on top
        remoteRenderer.setZOrderMediaOverlay(false); // Remote should be behind local (if local is overlay)
        Log.d(TAG, "LocalRenderer Z-order: MediaOverlay=" + true + ", OnTop=" + true);
        Log.d(TAG, "RemoteRenderer Z-order: MediaOverlay=" + false);

        localRenderer.setKeepScreenOn(true);
        remoteRenderer.setKeepScreenOn(true);
        // Mirror only local (front camera) for natural preview
        localRenderer.setMirror(true);
        remoteRenderer.setMirror(false);
        Log.d(TAG, "LocalRenderer mirrored: " + true + ", RemoteRenderer mirrored: " + false);

        InitializationOptions options = InitializationOptions.builder(getApplicationContext()).createInitializationOptions();
        PeerConnectionFactory.initialize(options);
        Log.d(TAG, "PeerConnectionFactory initialized.");

        DefaultVideoEncoderFactory encoderFactory =
                new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true);
        DefaultVideoDecoderFactory decoderFactory =
                new DefaultVideoDecoderFactory(eglBase.getEglBaseContext());

        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(factoryOptions)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory();
    }

    private void createLocalTracks() {
        // create SurfaceTextureHelper and VideoSource first
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.getEglBaseContext());
        videoSource = peerConnectionFactory.createVideoSource(false);
        // Adapt output to avoid oversized buffers vs. view and reduce buffer rejects
        videoSource.adaptOutputFormat(640, 480, 30);

        videoCapturer = createCameraCapturer();
        if (videoCapturer != null) {
            // initialize with videoSource.getCapturerObserver() (important)
            videoCapturer.initialize(surfaceTextureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoCapturer.startCapture(640, 480, 30);

            localVideoTrack = peerConnectionFactory.createVideoTrack("VIDEO_TRACK_ID", videoSource);
            localVideoTrack.setEnabled(true);
            // Ensure renderer visible immediately
            runOnUiThread(() -> {
                localRenderer.setVisibility(View.VISIBLE);
                localVideoTrack.addSink(localRenderer);
            });
        } else {
            Toast.makeText(this, "No camera available", Toast.LENGTH_SHORT).show();
        }

        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());
        localAudioTrack = peerConnectionFactory.createAudioTrack("AUDIO_TRACK_ID", audioSource);
    }

    private VideoCapturer createCameraCapturer() {
        // Prefer Camera2 if supported, else fallback to Camera1 for broader device/emulator support
        if (Camera2Enumerator.isSupported(this)) {
            Camera2Enumerator enumerator = new Camera2Enumerator(this);
            String[] deviceNames = enumerator.getDeviceNames();
            for (String name : deviceNames) {
                if (enumerator.isFrontFacing(name)) {
                    VideoCapturer c = enumerator.createCapturer(name, null);
                    if (c != null) return c;
                }
            }
            for (String name : deviceNames) {
                if (!enumerator.isFrontFacing(name)) {
                    VideoCapturer c = enumerator.createCapturer(name, null);
                    if (c != null) return c;
                }
            }
        } else {
            Camera1Enumerator enumerator = new Camera1Enumerator(true);
            String[] deviceNames = enumerator.getDeviceNames();
            for (String name : deviceNames) {
                if (enumerator.isFrontFacing(name)) {
                    VideoCapturer c = enumerator.createCapturer(name, null);
                    if (c != null) return c;
                }
            }
            for (String name : deviceNames) {
                if (!enumerator.isFrontFacing(name)) {
                    VideoCapturer c = enumerator.createCapturer(name, null);
                    if (c != null) return c;
                }
            }
        }
        return null;
    }

    private void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        
        // Multiple STUN servers for redundancy
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer());
        
        // TURN servers for NAT traversal (essential for physical device ↔ emulator)
        if (server_config.TURN_URL_UDP != null && !server_config.TURN_URL_UDP.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder(server_config.TURN_URL_UDP)
                    .setUsername(server_config.TURN_USERNAME)
                    .setPassword(server_config.TURN_PASSWORD)
                    .createIceServer());
        }
        if (server_config.TURN_URL_TCP != null && !server_config.TURN_URL_TCP.isEmpty()) {
            iceServers.add(PeerConnection.IceServer.builder(server_config.TURN_URL_TCP)
                    .setUsername(server_config.TURN_USERNAME)
                    .setPassword(server_config.TURN_PASSWORD)
                    .createIceServer());
        }

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.iceTransportsType = PeerConnection.IceTransportsType.ALL;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
        rtcConfig.iceCandidatePoolSize = 10;

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, new PeerConnectionObserver());
        if (peerConnection == null) {
            Toast.makeText(this, "Failed to create PeerConnection", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        if (localVideoTrack != null) peerConnection.addTrack(localVideoTrack);
        if (localAudioTrack != null) peerConnection.addTrack(localAudioTrack);
    }

    private void connectWebSocket() {
        String token = TokenManager.get(this);
        if (token == null) {
            Toast.makeText(this, "No token", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build WS URL robustly. server_config.WS_URL should include scheme+host+port+maybe /ws/
        String base = server_config.WS_URL;
        if (!base.endsWith("/")) base = base + "/";
        // join room by callId
        String full = base + "call/" + callId;
        Request.Builder reqBuilder = new Request.Builder().url(full);
        reqBuilder.header("Authorization", "Bearer " + token);
        Request request = reqBuilder.build();

        webSocket = httpClient.newWebSocket(request, new OkWebSocketListener());
        Log.d(TAG, "Connecting to WS: " + full);
    }

    private void createOfferAndSend() {
        MediaConstraints sdpConstraints = new MediaConstraints();
        // Unified Plan prefers transceivers; these flags are legacy but harmless

        peerConnection.createOffer(new SdpObserverImpl() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                // set local and dispatch
                peerConnection.setLocalDescription(new SdpObserverImpl(), sessionDescription);
                sendOfferViaRest(sessionDescription);
                sendOfferViaWebSocket(sessionDescription);
            }
        }, sdpConstraints);
    }

    private void sendOfferViaRest(SessionDescription sdp) {
        String token = TokenManager.get(this);
        if (token == null) return;

        JSONObject json = new JSONObject();
        try {
            JSONObject offer = new JSONObject();
            offer.put("sdp", sdp.description);
            offer.put("type", sdp.type.canonicalForm());
            json.put("call_id", callId);
            json.put("offer", offer);
            if (targetUid != null) json.put("target_uid", targetUid);
        } catch (JSONException e) {
            Log.e(TAG, "offer json error", e);
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/call/offer")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { Log.w(TAG, "REST offer failure", e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
        });
    }

    private void sendOfferViaWebSocket(SessionDescription sdp) {
        if (webSocket == null) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "offer");
            payload.put("call_id", callId);
            JSONObject offer = new JSONObject();
            offer.put("sdp", sdp.description);
            offer.put("type", sdp.type.canonicalForm());
            payload.put("offer", offer);
            if (targetUid != null) payload.put("target_uid", targetUid); // optional
        } catch (JSONException ignored) {}
        webSocket.send(payload.toString());
    }

    private void sendAnswerViaRest(SessionDescription sdp) {
        String token = TokenManager.get(this);
        if (token == null) return;

        JSONObject json = new JSONObject();
        try {
            JSONObject answer = new JSONObject();
            answer.put("sdp", sdp.description);
            answer.put("type", sdp.type.canonicalForm());
            json.put("call_id", callId);
            json.put("answer", answer);
            if (targetUid != null) json.put("target_uid", targetUid);
        } catch (JSONException e) {
            Log.e(TAG, "answer json error", e);
            return;
        }

        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/call/answer")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { Log.w(TAG, "REST answer failure", e); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
        });
    }

    private void sendAnswerViaWebSocket(SessionDescription sdp) {
        if (webSocket == null) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "answer");
            payload.put("call_id", callId);
            JSONObject answer = new JSONObject();
            answer.put("sdp", sdp.description);
            answer.put("type", sdp.type.canonicalForm());
            payload.put("answer", answer);
            if (targetUid != null) payload.put("target_uid", targetUid);
        } catch (JSONException ignored) {}
        webSocket.send(payload.toString());
    }

    private void sendIceCandidateViaWebSocket(IceCandidate c) {
        if (webSocket == null) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "candidate");
            payload.put("call_id", callId);
            JSONObject cand = new JSONObject();
            cand.put("sdpMid", c.sdpMid);
            cand.put("sdpMLineIndex", c.sdpMLineIndex);
            cand.put("candidate", c.sdp);
            payload.put("candidate", cand);
        } catch (JSONException ignored) {}
        webSocket.send(payload.toString());
    }

    private void sendEndCall() {
        if (webSocket == null) return;
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "end_call");
            payload.put("call_id", callId);
        } catch (JSONException ignored) {}
        webSocket.send(payload.toString());
    }

    private void sendCancelCall() {
        // Notify WS peers, and also REST cancel to inform callee via notify WS
        sendEndCall();
        String token = TokenManager.get(this);
        if (token == null) return;
        JSONObject json = new JSONObject();
        try { json.put("call_id", callId); } catch (JSONException ignored) {}
        RequestBody body = RequestBody.create(json.toString(), JSON);
        Request request = new Request.Builder()
                .url(server_config.BASE_URL + "/call/cancel")
                .addHeader("Authorization", "Bearer " + token)
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
        });
    }

    private void handleRemoteOffer(JSONObject offerObj) throws JSONException {
        String sdp = offerObj.getString("sdp");
        String type = offerObj.getString("type");
        SessionDescription sd = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
        peerConnection.setRemoteDescription(new SdpObserverImpl() {
            @Override public void onSetSuccess() { createAnswer(); }
        }, sd);
    }

    private void createAnswer() {
        MediaConstraints constraints = new MediaConstraints();

        peerConnection.createAnswer(new SdpObserverImpl() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SdpObserverImpl(), sessionDescription);
                sendAnswerViaRest(sessionDescription);
                sendAnswerViaWebSocket(sessionDescription);
            }
        }, constraints);
    }

    private void handleRemoteAnswer(JSONObject answerObj) throws JSONException {
        String sdp = answerObj.getString("sdp");
        String type = answerObj.getString("type");
        SessionDescription sd = new SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp);
        peerConnection.setRemoteDescription(new SdpObserverImpl(), sd);
    }

    private void handleRemoteCandidate(JSONObject candObj) throws JSONException {
        String sdpMid = candObj.getString("sdpMid");
        int sdpMLineIndex = candObj.getInt("sdpMLineIndex");
        String candidate = candObj.getString("candidate");
        IceCandidate ice = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
        peerConnection.addIceCandidate(ice);
    }

    private String parseTargetUid(String callId, String myUid) {
        if (callId == null) return null;
        String[] parts = callId.split("_");
        if (parts.length != 2) return null;
        return parts[0].equals(myUid) ? parts[1] : parts[0];
    }

    private void sendGroupCallSignal(String signal) {
        if (groupId == null || groupId.isEmpty()) return; // Only for group calls
        String token = TokenManager.get(this);
        if (token == null) return;
        try {
            JSONObject json = new JSONObject();
            json.put("group_id", groupId);
            json.put("message_content", signal);
            json.put("message_type", "call_signal");
            RequestBody body = RequestBody.create(json.toString(), JSON);
            Request req = new Request.Builder()
                    .url(server_config.BASE_URL + "/messagessendgroup")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(body)
                    .build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { Log.w(TAG, "Failed to send group call signal", e); }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException { response.close(); }
            });
        } catch (Exception ignored) {}
    }

    private void endCallCleanup() {
        if (webSocket != null) {
            webSocket.close(1000, "End");
            webSocket = null;
        }
        if (notifySocket != null) {
            try { notifySocket.close(1000, null); } catch (Exception ignored) {}
            notifySocket = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        if (videoCapturer != null) {
            try { videoCapturer.stopCapture(); } catch (InterruptedException ignored) {}
            videoCapturer.dispose();
            videoCapturer = null;
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper.dispose();
            surfaceTextureHelper = null;
        }
        if (videoSource != null) {
            videoSource.dispose();
            videoSource = null;
        }
        if (audioSource != null) {
            audioSource.dispose();
            audioSource = null;
        }
        if (localRenderer != null) {
            localRenderer.release();
            localRenderer = null;
        }
        if (remoteRenderer != null) {
            remoteRenderer.release();
            remoteRenderer = null;
        }
        if (peerConnectionFactory != null) {
            peerConnectionFactory.dispose();
            peerConnectionFactory = null;
        }
        if (eglBase != null) {
            eglBase.release();
            eglBase = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (callId != null && callId.startsWith("group_")) {
            sendGroupCallSignal("call_ended");
        }
        endCallCleanup();
    }

    private class OkWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            VideoCallActivity.this.webSocket = webSocket;
            Log.d(TAG, "WebSocket open");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject obj = new JSONObject(text);
                String type = obj.optString("type", "");
                if ("offer".equals(type) && obj.has("offer")) {
                    handleRemoteOffer(obj.getJSONObject("offer"));
                } else if ("answer".equals(type) && obj.has("answer")) {
                    handleRemoteAnswer(obj.getJSONObject("answer"));
                } else if ("candidate".equals(type) && obj.has("candidate")) {
                    handleRemoteCandidate(obj.getJSONObject("candidate"));
                } else if ("end_call".equals(type)) {
                    runOnUiThread(() -> {
                        Toast.makeText(VideoCallActivity.this, "Call ended by remote", Toast.LENGTH_SHORT).show();
                        endCallCleanup();
                        finish();
                    });
                } else if ("error".equals(type)) {
                    runOnUiThread(() -> Toast.makeText(VideoCallActivity.this, "Signaling error", Toast.LENGTH_SHORT).show());
                }
            } catch (JSONException e) {
                Log.w(TAG, "WS message parse error", e);
            }
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
            runOnUiThread(() -> Toast.makeText(VideoCallActivity.this, "WebSocket failure", Toast.LENGTH_SHORT).show());
            Log.e(TAG, "WS failure", t);
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(1000, null);
            Log.d(TAG, "WS closing: " + code + " / " + reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            VideoCallActivity.this.webSocket = null;
            Log.d(TAG, "WS closed: " + code + " / " + reason);
        }
    }

    private void connectNotifySocket() {
        String token = TokenManager.get(this);
        if (token == null) return;
        String base = server_config.WS_URL;
        if (!base.endsWith("/")) base += "/";
        String url = base + "notify";
        Request req = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        notifySocket = httpClient.newWebSocket(req, new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                try {
                    JSONObject obj = new JSONObject(text);
                    String type = obj.optString("type", "");
                    if ("call_rejected".equals(type) || "call_canceled".equals(type)) {
                        String id = obj.optString("call_id", "");
                        if (callId != null && callId.equals(id)) {
                            runOnUiThread(() -> {
                                Toast.makeText(VideoCallActivity.this, "call_rejected".equals(type) ? "Call was rejected" : "Call canceled", Toast.LENGTH_SHORT).show();
                                endCallCleanup();
                                finish();
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { if (notifySocket == webSocket) notifySocket = null; }
            @Override public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) { notifySocket = null; }
        });
    }

    private class PeerConnectionObserver implements PeerConnection.Observer {
        @Override 
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.d(TAG, "Signaling state: " + signalingState);
        }
        
        @Override 
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            Log.d(TAG, "ICE connection state: " + iceConnectionState);
            runOnUiThread(() -> {
                switch (iceConnectionState) {
                    case CONNECTED:
                        Toast.makeText(VideoCallActivity.this, "ICE Connected", Toast.LENGTH_SHORT).show();
                        break;
                    case FAILED:
                        Toast.makeText(VideoCallActivity.this, "ICE Failed - Check TURN server", Toast.LENGTH_LONG).show();
                        break;
                    case DISCONNECTED:
                        Toast.makeText(VideoCallActivity.this, "ICE Disconnected", Toast.LENGTH_SHORT).show();
                        break;
                }
            });
        }
        
        @Override 
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "ICE receiving: " + receiving);
        }
        
        @Override 
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
            Log.d(TAG, "ICE gathering: " + iceGatheringState);
        }
        
        @Override 
        public void onIceCandidate(IceCandidate iceCandidate) {
            Log.d(TAG, "New ICE candidate: " + iceCandidate.sdp);
            sendIceCandidateViaWebSocket(iceCandidate);
        }
        
        @Override 
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
            Log.d(TAG, "ICE candidates removed: " + iceCandidates.length);
        }
        
        @Override 
        public void onAddStream(org.webrtc.MediaStream mediaStream) {
            Log.d(TAG, "Stream added: " + mediaStream.videoTracks.size() + " video, " + mediaStream.audioTracks.size() + " audio");
            for (VideoTrack track : mediaStream.videoTracks) {
                Log.d(TAG, "  Video track ID: " + track.id() + ", enabled: " + track.enabled());
            }
            for (AudioTrack track : mediaStream.audioTracks) {
                Log.d(TAG, "  Audio track ID: " + track.id() + ", enabled: " + track.enabled());
            }
        }
        
        @Override 
        public void onRemoveStream(org.webrtc.MediaStream mediaStream) {
            Log.d(TAG, "Stream removed");
        }
        
        @Override 
        public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
        
        @Override 
        public void onRenegotiationNeeded() {
            Log.d(TAG, "Renegotiation needed");
        }
        
        @Override
        public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, org.webrtc.MediaStream[] mediaStreams) {
            Log.d(TAG, "Track added: " + rtpReceiver.track().kind());
            runOnUiThread(() -> {
                if (rtpReceiver != null && rtpReceiver.track() instanceof VideoTrack) {
                    VideoTrack remoteVideoTrack = (VideoTrack) rtpReceiver.track();
                    Log.d(TAG, "Remote video track added, enabling and adding to renderer");
                    remoteVideoTrack.setEnabled(true);
                    remoteRenderer.setVisibility(View.VISIBLE);
                    // Clear any previous image and then add the new sink
                    remoteRenderer.clearImage(); 
                    Log.d(TAG, "Attempting to add remote video track to renderer.");
                    remoteVideoTrack.addSink(remoteRenderer);
                    Log.d(TAG, "Remote video track added to renderer. Remote renderer visibility: " + remoteRenderer.getVisibility());
                    Toast.makeText(VideoCallActivity.this, "Remote video connected", Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Remote renderer visibility: " + remoteRenderer.getVisibility() + ", remote video track enabled: " + remoteVideoTrack.enabled());
                    Log.d(TAG, "Remote renderer measured dimensions: " + remoteRenderer.getMeasuredWidth() + "x" + remoteRenderer.getMeasuredHeight());
                } else if (rtpReceiver != null && rtpReceiver.track() instanceof AudioTrack) {
                    AudioTrack remoteAudioTrack = (AudioTrack) rtpReceiver.track();
                    Log.d(TAG, "Remote audio track added, enabling");
                    remoteAudioTrack.setEnabled(true);
                }
            });
        }
    }

    public class SdpObserverImpl implements SdpObserver {
        @Override 
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Log.d(TAG, "SDP create success: " + sessionDescription.type);
        }
        
        @Override 
        public void onSetSuccess() {
            Log.d(TAG, "SDP set success");
        }
        
        @Override 
        public void onCreateFailure(String s) { 
            Log.e(TAG, "SDP create failure: " + s); 
        }
        
        @Override 
        public void onSetFailure(String s) { 
            Log.e(TAG, "SDP set failure: " + s); 
        }
    }
}
