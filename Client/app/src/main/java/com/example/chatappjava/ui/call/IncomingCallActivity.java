package com.example.chatappjava.ui.call;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.chatappjava.R;
import com.example.chatappjava.config.server_config;
import com.example.chatappjava.utils.TokenManager;

import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;



public class IncomingCallActivity extends AppCompatActivity {
    private String myUid;
    private String callerUid;
    private String callId;
    private String callerName;

    private TextView txtCallerName;
    private TextView txtCallStatus;
    private ImageButton btnAcceptCall;
    private ImageButton btnRejectCall;

    private final OkHttpClient httpClient = new OkHttpClient();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private MediaPlayer ringtonePlayer;
    private Vibrator vibrator;
    private okhttp3.WebSocket notifySocket;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_call);

        txtCallerName = findViewById(R.id.txtCallerName);
        txtCallStatus = findViewById(R.id.txtCallStatus);
        btnAcceptCall = findViewById(R.id.btnAcceptCall);
        btnRejectCall = findViewById(R.id.btnRejectCall);

        myUid = getIntent().getStringExtra("MY_UID");
        callerUid = getIntent().getStringExtra("CALLER_UID");
        callId = getIntent().getStringExtra("CALL_ID");
        callerName = getIntent().getStringExtra("CALLER_NAME");

        if (callId == null && myUid != null && callerUid != null) {
            callId = myUid.compareTo(callerUid) < 0 ? myUid + "_" + callerUid : callerUid + "_" + myUid;
        }

        // Derive myUid from callId and callerUid if not provided
        if (myUid == null && callId != null && callerUid != null) {
            String[] parts = callId.split("_");
            if (parts.length == 2) {
                myUid = parts[0].equals(callerUid) ? parts[1] : parts[0];
            }
        }

        if (callerName != null) txtCallerName.setText(callerName);
        txtCallStatus.setText("Đang gọi đến...");

        startRingtone();

        btnAcceptCall.setOnClickListener(v -> acceptCall());
        btnRejectCall.setOnClickListener(v -> rejectCall());

        // Also listen to notify WS for caller cancellation
        connectNotifySocket();
    }

    private void startRingtone() {
        try {
            // Prefer system default ringtone for calls if custom raw not present
            Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (ringtoneUri == null) {
                // Try bundled raw resource if present, without referencing R.raw directly
                int resId = getResources().getIdentifier("incoming_call", "raw", getPackageName());
                if (resId != 0) {
                    ringtoneUri = Uri.parse("android.resource://" + getPackageName() + "/" + resId);
                } else {
                    // Fallback to notification/alarm defaults
                    ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    if (ringtoneUri == null) {
                        ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                    }
                }
            }

            ringtonePlayer = new MediaPlayer();
            ringtonePlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            ringtonePlayer.setDataSource(this, ringtoneUri);
            ringtonePlayer.setLooping(true);
            ringtonePlayer.setOnPreparedListener(MediaPlayer::start);
            ringtonePlayer.prepareAsync();

            // Ensure volume stream aligns with ringer
            setVolumeControlStream(AudioManager.STREAM_RING);
        } catch (Exception ignored) {
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator != null) {
            long[] pattern = {0, 1000, 1000};
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                VibrationEffect effect = VibrationEffect.createWaveform(pattern, 0);
                vibrator.vibrate(effect);
            } else {
                vibrator.vibrate(pattern, 0);
            }
        }
    }

    private void stopRingtone() {
        if (ringtonePlayer != null) {
            try { ringtonePlayer.stop(); } catch (Exception ignored) {}
            try { ringtonePlayer.release(); } catch (Exception ignored) {}
            ringtonePlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
    }

    private void acceptCall() {
        stopRingtone();
        if (myUid == null || callId == null) {
            Toast.makeText(this, "Thiếu dữ liệu cuộc gọi", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent i = new Intent(this, VideoCallActivity.class);
        i.putExtra("USER_ID", myUid);
        i.putExtra("CALL_ID", callId);
        i.putExtra("IS_CALLER", false);
        i.putExtra("REMOTE_UID", callerUid);
        i.putExtra("REMOTE_NAME", callerName);
        startActivity(i);
        finish();
    }

    private void rejectCall() {
        stopRingtone();
        String token = TokenManager.get(this);
        if (token == null || callId == null) {
            finish();
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("call_id", callId);
            RequestBody body = RequestBody.create(obj.toString(), JSON);
            Request request = new Request.Builder()
                    .url(server_config.BASE_URL + "/call/reject")
                    .addHeader("Authorization", "Bearer " + token)
                    .post(body)
                    .build();
            httpClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) { runOnUiThread(() -> finish()); }
                @Override public void onResponse(Call call, Response response) {
                    response.close();
                    runOnUiThread(() -> finish());
                }
            });
        } catch (JSONException e) {
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        stopRingtone();
        if (notifySocket != null) {
            try { notifySocket.close(1000, null); } catch (Exception ignored) {}
            notifySocket = null;
        }
        super.onDestroy();
    }

    private void connectNotifySocket() {
        String token = TokenManager.get(this);
        if (token == null) return;
        String base = server_config.WS_URL;
        if (!base.endsWith("/")) base += "/";
        String url = base + "notify";
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + token)
                .build();
        notifySocket = httpClient.newWebSocket(req, new okhttp3.WebSocketListener() {
            @Override public void onMessage(okhttp3.WebSocket webSocket, String text) {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject(text);
                    if ("call_canceled".equals(obj.optString("type"))) {
                        String canceledId = obj.optString("call_id", "");
                        if (callId != null && callId.equals(canceledId)) {
                            runOnUiThread(() -> {
                                Toast.makeText(IncomingCallActivity.this, "Cuộc gọi đã bị hủy", Toast.LENGTH_SHORT).show();
                                finish();
                            });
                        }
                    }
                } catch (Exception ignored) {}
            }
            @Override public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) { if (notifySocket == webSocket) notifySocket = null; }
            @Override public void onFailure(okhttp3.WebSocket webSocket, Throwable t, Response response) { notifySocket = null; }
        });
    }
}
