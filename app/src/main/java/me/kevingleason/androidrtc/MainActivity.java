package me.kevingleason.androidrtc;

import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pubnub.api.PNConfiguration;


import com.pubnub.api.PubNub;
import com.pubnub.api.callbacks.PNCallback;
import com.pubnub.api.callbacks.SubscribeCallback;
import com.pubnub.api.models.consumer.PNPublishResult;
import com.pubnub.api.models.consumer.PNStatus;
import com.pubnub.api.models.consumer.presence.PNGetStateResult;
import com.pubnub.api.models.consumer.presence.PNHereNowChannelData;
import com.pubnub.api.models.consumer.presence.PNHereNowOccupantData;
import com.pubnub.api.models.consumer.presence.PNHereNowResult;
import com.pubnub.api.models.consumer.presence.PNSetStateResult;
import com.pubnub.api.models.consumer.pubsub.PNMessageResult;
import com.pubnub.api.models.consumer.pubsub.PNPresenceEventResult;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import me.kevingleason.androidrtc.adapters.HistoryAdapter;
import me.kevingleason.androidrtc.adt.HistoryItem;
import me.kevingleason.androidrtc.util.Constants;

import static android.R.id.message;
import static me.kevingleason.androidrtc.R.drawable.pubnub;


public class MainActivity extends ListActivity {
    private final String TAG = getClass().getSimpleName();
    private SharedPreferences mSharedPreferences;
    private String username;
    private String stdByChannel;
    private PubNub mPubNub;

    private ListView mHistoryList;
    private HistoryAdapter mHistoryAdapter;
    private EditText mCallNumET;
    private TextView mUsernameTV;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        this.mSharedPreferences = getSharedPreferences(Constants.SHARED_PREFS, MODE_PRIVATE);
        if (!this.mSharedPreferences.contains(Constants.USER_NAME)){
            Intent intent = new Intent(this, LoginActivity.class);
            startActivity(intent);
            finish();
            return;
        }
        this.username     = this.mSharedPreferences.getString(Constants.USER_NAME, "");
        this.stdByChannel = this.username + Constants.STDBY_SUFFIX;

        this.mHistoryList = getListView();
        this.mCallNumET   = (EditText) findViewById(R.id.call_num);
        this.mUsernameTV  = (TextView) findViewById(R.id.main_username);

        this.mUsernameTV.setText(this.username);
        initPubNub();

        this.mHistoryAdapter = new HistoryAdapter(this, new ArrayList<HistoryItem>(), this.mPubNub);
        this.mHistoryList.setAdapter(this.mHistoryAdapter);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch(id){
            case R.id.action_settings:
                return true;
            case R.id.action_sign_out:
                signOut();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if(this.mPubNub!=null){
            this.mPubNub.unsubscribeAll();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if(this.mPubNub==null){
            initPubNub();
        } else {
            subscribeStdBy();
        }
    }

    /**
     * Subscribe to standby channel so that it doesn't interfere with the WebRTC Signaling.
     */
    public void initPubNub(){
        PNConfiguration pnConfiguration = new PNConfiguration();
        pnConfiguration.setSubscribeKey(Constants.SUB_KEY);
        pnConfiguration.setPublishKey(Constants.PUB_KEY);
        pnConfiguration.setUuid(username);
        this.mPubNub  = new PubNub(pnConfiguration);
        subscribeStdBy();
    }

    /**
     * Subscribe to standby channel
     */
    private void subscribeStdBy(){
        try {
              Log.d(TAG,"subscribeStdBy -> Adding Listener ");
              this.mPubNub.addListener(new SubscribeCallback() {
                  @Override
                  public void status(PubNub pubnub, PNStatus status) {
                      Log.d("MA-iPN", "STATUS: " + status.toString());

                  }

                  @Override
                  public void message(PubNub pubnub, PNMessageResult message) {
                      Log.d("MA-iPN", "MESSAGE: " + message.toString());
                    if (!(message.getMessage() instanceof JsonNode)) return; // Ignore if not JSONObject
                      JsonNode jsonMsg = message.getMessage();
                    try {
                        if (!jsonMsg.has(Constants.JSON_CALL_USER)) return;     //Ignore Signaling messages.
                        JsonNode user = jsonMsg.get (Constants.JSON_CALL_USER);
                        dispatchIncomingCall(user.asText());
                    } catch (Exception e){
                        e.printStackTrace();
                    }
                  }

                  @Override
                  public void presence(PubNub pubnub, PNPresenceEventResult presence) {
                      Log.d("MA-iPN", "STATUS: " + presence.toString());

                  }
              });
            this.mPubNub.subscribe().channels(Arrays.asList(this.stdByChannel)).execute();
//            this.mPubNub.subscribe(this.stdByChannel, new Callback() {
//                @Override
//                public void successCallback(String channel, Object message) {
//                    Log.d("MA-iPN", "MESSAGE: " + message.toString());
//                    if (!(message instanceof JSONObject)) return; // Ignore if not JSONObject
//                    JSONObject jsonMsg = (JSONObject) message;
//                    try {
//                        if (!jsonMsg.has(Constants.JSON_CALL_USER)) return;     //Ignore Signaling messages.
//                        String user = jsonMsg.getString(Constants.JSON_CALL_USER);
//                        dispatchIncomingCall(user);
//                    } catch (JSONException e){
//                        e.printStackTrace();
//                    }
//                }
//
//                @Override
//                public void connectCallback(String channel, Object message) {
//                    Log.d("MA-iPN", "CONNECTED: " + message.toString());
//                    setUserStatus(Constants.STATUS_AVAILABLE);
//                }
//
//                @Override
//                public void errorCallback(String channel, PubnubError error) {
//                    Log.d("MA-iPN","ERROR: " + error.toString());
//                }
//            });
        } catch (Exception e){
            Log.d("HERE","HEREEEE");
            e.printStackTrace();
        }
    }

    /**
     * Take the user to a video screen. USER_NAME is a required field.
     * @param view button that is clicked to trigger toVideo
     */
    public void makeCall(View view){
        String callNum = mCallNumET.getText().toString();
        if (callNum.isEmpty() || callNum.equals(this.username)){
            showToast("Enter a valid user ID to call.");
            return;
        }
        dispatchCall(callNum);
    }

    /**TODO: Debate who calls who. Should one be on standby? Or use State API for busy/available
     * Check that user is online. If they are, dispatch the call by publishing to their standby
     *   channel. If the publish was successful, then change activities over to the video chat.
     * The called user will then have the option to accept of decline the call. If they accept,
     *   they will be brought to the video chat activity as well, to connect video/audio. If
     *   they decline, a hangup will be issued, and the VideoChat adapter's onHangup callback will
     *   be invoked.
     * @param callNum Number to publish a call to.
     */
    public void dispatchCall(final String callNum){
        final String callNumStdBy = callNum + Constants.STDBY_SUFFIX;

        mPubNub.hereNow()
                .channels(Arrays.asList(callNumStdBy))
                .includeUUIDs(true)
                .async(new PNCallback<PNHereNowResult>() {
                    @Override
                    public void onResponse(PNHereNowResult result, PNStatus status) {
                        if (status.isError()){
                            Log.e(TAG, "Erro: " + status.getErrorData().toString());
                            return;
                        }


                        for (PNHereNowChannelData channelData : result.getChannels().values()) {
                            System.out.println("---");
                            System.out.println("channel:" + channelData.getChannelName());
                            System.out.println("occoupancy: " + channelData.getOccupancy());

                            int occupancy = channelData.getOccupancy();
                            if (occupancy == 0) {
                                showToast("User is not online!");
                                return;
                            }

                            System.out.println("occupants:");
                            for (PNHereNowOccupantData occupant : channelData.getOccupants()) {
                                System.out.println("uuid: " + occupant.getUuid() + " state: " + occupant.getState());
                            }

//                            using JsonNodeFactory as thought in https://www.pubnub.com/docs/android/api-reference-sdk-v4#publish
                            JsonNodeFactory factory = JsonNodeFactory.instance;
                            ObjectNode jsonCall = factory.objectNode();
                            jsonCall.put(Constants.JSON_CALL_USER,username);
                            jsonCall.put(Constants.JSON_CALL_TIME,System.currentTimeMillis());
                            mPubNub.publish()
                                    .message(jsonCall)
                                    .channel(callNumStdBy)
                                    .async(new PNCallback<PNPublishResult>() {
                                        @Override
                                        public void onResponse(PNPublishResult result, PNStatus status) {
                                            if(status.isError()){
                                                Log.e(TAG, "Erro: " + status.getErrorData().toString());
                                                return;
                                            }
                                            Intent intent = new Intent(MainActivity.this, VideoChatActivity.class);
                                            Log.i(TAG, "Putting Extra USER_NAME: " + username);
                                            Log.i(TAG, "Putting Extra CALL_USER: " + callNum);
                                            intent.putExtra(Constants.USER_NAME, username);
                                            intent.putExtra(Constants.CALL_USER, callNum);  // Only accept from this number?
                                            startActivity(intent);
                                        }
                                    });

                        }
                    }
                });

//        this.mPubNub.hereNow(callNumStdBy, new Callback() {
//            public final String LOG_TAG = getClass().getSimpleName();
//
//            @Override
//            public void successCallback(String channel, Object message) {
//                Log.d("MA-dC", "HERE_NOW: " +" CH - " + callNumStdBy + " " + message.toString());
//                try {
//                    int occupancy = ((JSONObject) message).getInt(Constants.JSON_OCCUPANCY);
//                    if (occupancy == 0) {
//                        showToast("User is not online!");
//                        return;
//                    }
//                    JSONObject jsonCall = new JSONObject();
//                    jsonCall.put(Constants.JSON_CALL_USER, username);
//                    jsonCall.put(Constants.JSON_CALL_TIME, System.currentTimeMillis());
//                    mPubNub.publish(callNumStdBy, jsonCall, new Callback() {
//                        @Override
//                        public void successCallback(String channel, Object message) {
//                            Log.d("MA-dC", "SUCCESS: " + message.toString());
//                            Intent intent = new Intent(MainActivity.this, VideoChatActivity.class);
//                            Log.i(LOG_TAG, "Putting Extra USER_NAME: " + username);
//                            Log.i(LOG_TAG, "Putting Extra CALL_USER: " + callNum);
//                            intent.putExtra(Constants.USER_NAME, username);
//                            intent.putExtra(Constants.CALL_USER, callNum);  // Only accept from this number?
//                            startActivity(intent);
//                        }
//                    });
//                } catch (JSONException e) {
//                    e.printStackTrace();
//                }
//            }
//        });

    }

    /**
     * Handle incoming calls. TODO: Implement an accept/reject functionality.
     * @param userId
     */
    private void dispatchIncomingCall(String userId){
//        showToast("Call from: " + userId);
//        Intent intent = new Intent(MainActivity.this, IncomingCallActivity.class);
//        intent.putExtra(Constants.USER_NAME, username);
//        intent.putExtra(Constants.CALL_USER, userId);
//        startActivity(intent);

        Intent intent = new Intent(this, VideoChatActivity.class);
        intent.putExtra(Constants.USER_NAME, this.username);
        intent.putExtra(Constants.CALL_USER, userId);
        startActivity(intent);
    }

    private void setUserStatus(String status){
        final Map<String, Object> state = new HashMap<>();
        state.put(Constants.JSON_STATUS, status);
        mPubNub.setPresenceState()
                .channels(Arrays.asList(this.stdByChannel, this.username)) // apply on those channels
                .state(state) // the new state
                .async(new PNCallback<PNSetStateResult>() {
                    @Override
                    public void onResponse(PNSetStateResult result, PNStatus status) {
                        if (status.isError()) {
                            Log.e(TAG, "Erro: " + status.getErrorData().toString());
                            return;
                        }

                    Log.d("MA-sUS","State Set: " + result.getState().toString());

                    }
                });

//            JSONObject state = new JSONObject();
//            state.put(Constants.JSON_STATUS, status);
//            this.mPubNub.setState(this.stdByChannel, this.username, state, new Callback() {
//                @Override
//                public void successCallback(String channel, Object message) {
//                    Log.d("MA-sUS","State Set: " + message.toString());
//                }
//            });
    }

    private void getUserStatus(String userId){
        String stdByUser = userId + Constants.STDBY_SUFFIX;
        this.mPubNub.getPresenceState()
                .channels(Arrays.asList(stdByUser,stdByChannel))
                .uuid(userId)
                .async(new PNCallback<PNGetStateResult>() {
                    @Override
                    public void onResponse(PNGetStateResult result, PNStatus status) {
                        if (status.isError()){
                            Log.e(TAG, "Erro: " + status.getErrorData().toString());
                            return;
                        }
                        Log.d("MA-gUS", "User Status: " + result.toString());

                    }
                });
    }

    /**
     * Ensures that toast is run on the UI thread.
     * @param message
     */
    private void showToast(final String message){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Log out, remove username from SharedPreferences, unsubscribe from PubNub, and send user back
     *   to the LoginActivity
     */
    public void signOut(){
        this.mPubNub.unsubscribeAll();
        SharedPreferences.Editor edit = this.mSharedPreferences.edit();
        edit.remove(Constants.USER_NAME);
        edit.apply();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("oldUsername", this.username);
        startActivity(intent);
    }
}
