package me.kevingleason.androidrtc;

import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoRendererGui;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import me.kevingleason.androidrtc.adapters.ChatAdapter;
import me.kevingleason.androidrtc.adt.ChatMessage;
import me.kevingleason.androidrtc.servers.XirSysRequest;
import me.kevingleason.androidrtc.util.Constants;
import me.kevingleason.androidrtc.util.LogRTCListener;
import me.kevingleason.pnwebrtc.PnPeer;
import me.kevingleason.pnwebrtc.PnRTCClient;
import me.kevingleason.pnwebrtc.PnSignalingParams;

/**
 * This chat will begin/subscribe to a video chat.
 * REQUIRED: The intent must contain a
 */
public class VideoChatActivity extends ListActivity {
    public static final String VIDEO_TRACK_ID = "videoPN";
    public static final String AUDIO_TRACK_ID = "audioPN";
    public static final String LOCAL_MEDIA_STREAM_ID = "localStreamPN";
    private final String LOG_TAG = getClass().getSimpleName();

    private PnRTCClient pnRTCClient;
    private VideoSource localVideoSource;
    private VideoRenderer.Callbacks localRender;
    private VideoRenderer.Callbacks remoteRender;
    private GLSurfaceView videoView;
    private EditText mChatEditText;
    private ListView mChatList;
    private ChatAdapter mChatAdapter;
    private TextView mCallStatus;

    private String username;
    private boolean backPressed = false;
    private Thread  backPressedThread = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(LOG_TAG,"onCreate...");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_chat);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Bundle extras = getIntent().getExtras();
        if (extras == null || !extras.containsKey(Constants.USER_NAME)) {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            Toast.makeText(this, "Need to pass username to VideoChatActivity in intent extras (Constants.USER_NAME).",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        this.username      = extras.getString(Constants.USER_NAME, "");
        this.mChatList     = getListView();
        this.mChatEditText = (EditText) findViewById(R.id.chat_input);
        this.mCallStatus   = (TextView) findViewById(R.id.call_status);

        // Set up the List View for chatting
        List<ChatMessage> ll = new LinkedList<ChatMessage>();
        mChatAdapter = new ChatAdapter(this, ll);
        mChatList.setAdapter(mChatAdapter);

        Log.i(LOG_TAG,"PeerConnectionFactory.initializeAndroidGlobals");

        // First, we initiate the PeerConnectionFactory with our application context and some options.
        PeerConnectionFactory.initializeAndroidGlobals(
                this,  // Context
                true,  // Audio Enabled
                true,  // Video Enabled
                true,  // Hardware Acceleration Enabled
                null); // Render EGL Context

        Log.i(LOG_TAG,"new PeerConnectionFactory");
        PeerConnectionFactory pcFactory = new PeerConnectionFactory();

        Log.i(LOG_TAG,"pnRTCClient...");
        this.pnRTCClient = new PnRTCClient(Constants.PUB_KEY, Constants.SUB_KEY, this.username);

        Log.i(LOG_TAG,"getXirSysIceServers");
        List<PeerConnection.IceServer> servers = getXirSysIceServers();
        if (!servers.isEmpty()){
            Log.i(LOG_TAG,"server not empty");
            Log.i(LOG_TAG,"setSignalParams");
            this.pnRTCClient.setSignalParams(new PnSignalingParams());
        }



        // Returns the number of cams & front/back face device name
        Log.i(LOG_TAG,"local video code");
        int camNumber = VideoCapturerAndroid.getDeviceCount();
        String frontFacingCam = VideoCapturerAndroid.getNameOfFrontFacingDevice();
        String backFacingCam = VideoCapturerAndroid.getNameOfBackFacingDevice();


        // Creates a VideoCapturerAndroid instance for the device name
        VideoCapturer capturer = VideoCapturerAndroid.create(frontFacingCam);

        // First create a Video Source, then we can make a Video Track
        localVideoSource = pcFactory.createVideoSource(capturer, this.pnRTCClient.videoConstraints());
        VideoTrack localVideoTrack = pcFactory.createVideoTrack(VIDEO_TRACK_ID, localVideoSource);

        Log.i(LOG_TAG,"local audio code");
        // First we create an AudioSource then we can create our AudioTrack
        AudioSource audioSource = pcFactory.createAudioSource(this.pnRTCClient.audioConstraints());
        AudioTrack localAudioTrack = pcFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource);

        Log.i(LOG_TAG,"video renderer part");
        // To create our VideoRenderer, we can use the included VideoRendererGui for simplicity
        // First we need to set the GLSurfaceView that it should render to
        this.videoView = (GLSurfaceView) findViewById(R.id.gl_surface);

        Log.i(LOG_TAG,"VideoRendererGui.setView");
        // Then we set that view, and pass a Runnable to run once the surface is ready
        VideoRendererGui.setView(videoView, null);

        // Now that VideoRendererGui is ready, we can get our VideoRenderer.
        // IN THIS ORDER. Effects which is on top or bottom
        remoteRender = VideoRendererGui.create(0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, false);
        localRender = VideoRendererGui.create(0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, true);

        // We start out with an empty MediaStream object, created with help from our PeerConnectionFactory
        //  Note that LOCAL_MEDIA_STREAM_ID can be any string
        MediaStream mediaStream = pcFactory.createLocalMediaStream(LOCAL_MEDIA_STREAM_ID);



        // Now we can add our tracks.
        Log.i(LOG_TAG,"adding tracks");
        mediaStream.addTrack(localVideoTrack);
        mediaStream.addTrack(localAudioTrack);

        Log.i(LOG_TAG,"attach RTC Listener to PubNub Client");
        // First attach the RTC Listener so that callback events will be triggered
        this.pnRTCClient.attachRTCListener(new DemoRTCListener());

        // Then attach your local media stream to the PnRTCClient.
        //  This will trigger the onLocalStream callback.

        Log.i(LOG_TAG,"attachLocalMediaStream");
        this.pnRTCClient.attachLocalMediaStream(mediaStream);

//        // this is used to send stream to remote...
//        PeerConnection peerConnection = pcFactory.createPeerConnection(servers, pnRTCClient.pcConstraints(), new PeerConnection.Observer() {
//            private final String LOG_TAG = getClass().getSimpleName();
//            @Override
//            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
//            }
//
//            @Override
//            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
//                Log.i(LOG_TAG,"onSignalingChange");
//
//            }
//
//            @Override
//            public void onIceConnectionReceivingChange(boolean b) {
//                Log.i(LOG_TAG,"onIceConnectionReceivingChange");
//
//            }
//
//            @Override
//            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
//                Log.i(LOG_TAG,"onIceGatheringChange");
//
//            }
//
//            @Override
//            public void onIceCandidate(IceCandidate iceCandidate) {
//                Log.i(LOG_TAG,"onIceCandidate");
//
//            }
//
//            @Override
//            public void onAddStream(MediaStream mediaStream) {
//                Log.i(LOG_TAG,"onAddStream");
//
//            }
//
//            @Override
//            public void onRemoveStream(MediaStream mediaStream) {
//                Log.i(LOG_TAG,"onRemoveStream");
//
//            }
//
//            @Override
//            public void onDataChannel(DataChannel dataChannel) {
//                Log.i(LOG_TAG,"onDataChannel");
//
//            }
//
//            @Override
//            public void onRenegotiationNeeded() {
//                Log.i(LOG_TAG,"onRenegotiationNeeded");
//
//            }
//        });
//
//        peerConnection.addStream(mediaStream);

        Log.i(LOG_TAG,"listen on a channel");
        // Listen on a channel. This is your "phone number," also set the max chat users.

        //// TODO: 26/08/16 Change this Kevin...
        this.pnRTCClient.listenOn(username);
        this.pnRTCClient.setMaxConnections(1);


        // If the intent contains a number to dial, call it now that you are connected.
        //  Else, remain listening for a call.
        if (extras.containsKey(Constants.CALL_USER)) {
            String callUser = extras.getString(Constants.CALL_USER, "");
            Log.i(LOG_TAG,"connecting to user");
            connectToUser(callUser);
        }
        Log.i(LOG_TAG,"... onCreate");

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_video_chat, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.videoView.onPause();
        this.localVideoSource.stop();
    }

    @Override
    protected void onResume() {
        Log.i(LOG_TAG,"onResume");

        super.onResume();
        this.videoView.onResume();
        this.localVideoSource.restart();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG,"onDestroy");
        if (this.localVideoSource != null) {
            this.localVideoSource.stop();
        }
        if (this.pnRTCClient != null) {
            this.pnRTCClient.onDestroy();
        }
    }

    @Override
    public void onBackPressed() {
        if (!this.backPressed){
            this.backPressed = true;
            Toast.makeText(this,"Press back again to end.",Toast.LENGTH_SHORT).show();
            this.backPressedThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(5000);
                        backPressed = false;
                    } catch (InterruptedException e){ Log.d("VCA-oBP","Successfully interrupted"); }
                }
            });
            this.backPressedThread.start();
            return;
        }
        if (this.backPressedThread != null)
            this.backPressedThread.interrupt();
        super.onBackPressed();
    }

    public List<PeerConnection.IceServer> getXirSysIceServers(){
        Log.i(LOG_TAG,"getXirSysIceServers...");
        List<PeerConnection.IceServer> servers = new ArrayList<PeerConnection.IceServer>();
        try {
            servers = new XirSysRequest().execute().get();
        } catch (InterruptedException e){
            e.printStackTrace();
        }catch (ExecutionException e){
            e.printStackTrace();
        }
        Log.i(LOG_TAG,"...getXirSysIceServers");
        return servers;
    }

    public void connectToUser(String user) {
        this.pnRTCClient.connect(user);
    }

    public void hangup(View view) {
        this.pnRTCClient.closeAllConnections();
        endCall();
    }

    private void endCall() {
        startActivity(new Intent(VideoChatActivity.this, MainActivity.class));
        finish();
    }


    public void sendMessage(View view) {
        String message = mChatEditText.getText().toString();
        if (message.equals("")) return; // Return if empty
        ChatMessage chatMsg = new ChatMessage(this.username, message, System.currentTimeMillis());
        mChatAdapter.addMessage(chatMsg);
        JSONObject messageJSON = new JSONObject();
        try {
            messageJSON.put(Constants.JSON_MSG_UUID, chatMsg.getSender());
            messageJSON.put(Constants.JSON_MSG, chatMsg.getMessage());
            messageJSON.put(Constants.JSON_TIME, chatMsg.getTimeStamp());
            this.pnRTCClient.transmitAll(messageJSON);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        // Hide keyboard when you send a message.
        View focusView = this.getCurrentFocus();
        if (focusView != null) {
            InputMethodManager inputManager = (InputMethodManager) this.getSystemService(Context.INPUT_METHOD_SERVICE);
            inputManager.hideSoftInputFromWindow(view.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
        }
        mChatEditText.setText("");
    }

    /**
     * LogRTCListener is used for debugging purposes, it prints all RTC messages.
     * DemoRTC is just a Log Listener with the added functionality to append screens.
     */
    private class DemoRTCListener extends LogRTCListener {

        private final String LOG_TAG = getClass().getSimpleName();
        @Override
        public void onLocalStream(final MediaStream localStream) {
            Log.i(LOG_TAG,"onLocalStream");

            super.onLocalStream(localStream); // Will log values
            VideoChatActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if(localStream.videoTracks.size()==0) {
                        Log.e(LOG_TAG,"Sem local streams!" );
                        return;
                    }else{
                        localStream.videoTracks.get(0).addRenderer(new VideoRenderer(localRender));
                    }
                }
            });
        }

        @Override
        public void onAddRemoteStream(final MediaStream remoteStream, final PnPeer peer) {
            Log.i(LOG_TAG,"onAddRemoteStream");
            VideoChatActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(VideoChatActivity.this,"Connected to " + peer.getId(), Toast.LENGTH_SHORT).show();
                    try {
                        if(remoteStream.audioTracks.size()==0 || remoteStream.videoTracks.size()==0) return;
                        mCallStatus.setVisibility(View.GONE);
                        remoteStream.videoTracks.get(0).addRenderer(new VideoRenderer(remoteRender));
                        VideoRendererGui.update(remoteRender, 0, 0, 100, 100, VideoRendererGui.ScalingType.SCALE_ASPECT_FILL, false);
                        VideoRendererGui.update(localRender, 72, 65, 25, 25, VideoRendererGui.ScalingType.SCALE_ASPECT_FIT, true);
                    }
                    catch (Exception e){ e.printStackTrace(); }
                }
            });
            super.onAddRemoteStream(remoteStream, peer); // Will log values

        }

        @Override
        public void onMessage(PnPeer peer, Object message) {
            Log.i(LOG_TAG,"onMessage");
            super.onMessage(peer, message);  // Will log values
            if (!(message instanceof JSONObject)) return; //Ignore if not JSONObject
            JSONObject jsonMsg = (JSONObject) message;
            try {
                String uuid = jsonMsg.getString(Constants.JSON_MSG_UUID);
                String msg  = jsonMsg.getString(Constants.JSON_MSG);
                long   time = jsonMsg.getLong(Constants.JSON_TIME);
                final ChatMessage chatMsg = new ChatMessage(uuid, msg, time);
                VideoChatActivity.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mChatAdapter.addMessage(chatMsg);
                    }
                });
            } catch (JSONException e){
                e.printStackTrace();
            }
        }

        @Override
        public void onPeerConnectionClosed(PnPeer peer) {
            Log.i(LOG_TAG,"onPeerConnectionClosed");
            super.onPeerConnectionClosed(peer);
            VideoChatActivity.this.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mCallStatus.setText("Call Ended...");
                    mCallStatus.setVisibility(View.VISIBLE);
                }
            });
            try {Thread.sleep(1500);} catch (InterruptedException e){e.printStackTrace();}
            Intent intent = new Intent(VideoChatActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }
    }
}