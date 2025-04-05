package com.example.chronocode;// BattleActivity.java
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class BattleActivity extends AppCompatActivity {

    private static final String TAG = "BattleActivity";
    private static final long BATTLE_DURATION_MS = TimeUnit.MINUTES.toMillis(5); // 5 minutes battle

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private String roomId;
    private String opponentName;
    private String myUid;
    private String myPlayerKey; // "player1" or "player2"
    private String opponentPlayerKey; // "player2" or "player1"

    private TextView problemTitleText, problemDescriptionText;
    private TextView timerText;
    private TextView myScoreText, opponentScoreText;
    private EditText codeInputEditText;
    private Button submitButton;

    private ListenerRegistration battleStateListener;
    private CountDownTimer battleTimer;
    private boolean battleFinished = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_battle); // Ensure you have activity_battle.xml

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        currentUser = mAuth.getCurrentUser();

        if (currentUser == null) {
            // Should not happen if MainActivity ensures login, but handle defensively
            Toast.makeText(this, "Error: Not logged in.", Toast.LENGTH_SHORT).show();
            finish(); // Close activity
            return;
        }
        myUid = currentUser.getUid();

        roomId = getIntent().getStringExtra("ROOM_ID");
        opponentName = getIntent().getStringExtra("OPPONENT_NAME");

        if (roomId == null) {
            Toast.makeText(this, "Error: Invalid battle room.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Initialize UI elements (findViewByid)
        problemTitleText = findViewById(R.id.problemTitleText);
        problemDescriptionText = findViewById(R.id.problemDescriptionText);
        timerText = findViewById(R.id.timerText);
        myScoreText = findViewById(R.id.myScoreText);
        opponentScoreText = findViewById(R.id.opponentScoreText);
        codeInputEditText = findViewById(R.id.codeInputEditText);
        submitButton = findViewById(R.id.submitButton);

        // Set opponent name display (if you have a TextView for it)
        TextView opponentNameText = findViewById(R.id.opponentNameText); // Add in XML
        if (opponentNameText != null) {
            opponentNameText.setText("Opponent: " + (opponentName != null ? opponentName : "Unknown"));
        }

        submitButton.setOnClickListener(v -> submitCode());

        listenToBattleState();
    }

    private void listenToBattleState() {
        DocumentReference roomRef = db.collection("battle_rooms").document(roomId);
        battleStateListener = roomRef.addSnapshotListener((snapshot, e) -> {
            if (e != null) {
                Log.w(TAG, "Battle listener failed.", e);
                handleBattleEnd("Error"); // End battle on error
                return;
            }

            if (snapshot != null && snapshot.exists()) {
                if(battleFinished) return; // Don't process updates if already finished locally

                // Determine if I am player1 or player2 for easy field access
                if (myPlayerKey == null) { // Determine only once
                    if (myUid.equals(snapshot.getString("player1_uid"))) {
                        myPlayerKey = "player1";
                        opponentPlayerKey = "player2";
                    } else if (myUid.equals(snapshot.getString("player2_uid"))) {
                        myPlayerKey = "player2";
                        opponentPlayerKey = "player1";
                    } else {
                        Log.e(TAG, "User " + myUid + " not found in room " + roomId);
                        handleBattleEnd("Error"); // Critical error
                        return;
                    }
                }

                // Load Problem Info (if not already loaded)
                if (problemTitleText.getText().toString().isEmpty()) {
                    String problemId = snapshot.getString("problemId");
                    if (problemId != null) loadProblem(problemId);
                }

                // Start Timer (if not already started and startTime exists)
                if (battleTimer == null && snapshot.getTimestamp("startTime") != null) {
                    long startTime = snapshot.getTimestamp("startTime").toDate().getTime();
                    long currentTime = System.currentTimeMillis();
                    long elapsedTime = currentTime - startTime;
                    long remainingTime = BATTLE_DURATION_MS - elapsedTime;

                    if(remainingTime > 0) {
                        startTimer(remainingTime);
                    } else {
                        // Timer already expired when joining? Or edge case.
                        updateTimerDisplay(0);
                        handleBattleEnd("Timeout");
                    }
                }

                // Update Scores
                Long myScore = snapshot.getLong(myPlayerKey + "_score");
                Long opponentScore = snapshot.getLong(opponentPlayerKey + "_score");
                myScoreText.setText("My Score: " + (myScore != null ? myScore : 0));
                opponentScoreText.setText(opponentName + " Score: " + (opponentScore != null ? opponentScore : 0));


                // Check for Battle End Conditions based on Firestore state
                String status = snapshot.getString("status");
                if ("finished".equals(status)) {
                    handleBattleEnd(snapshot.getString("winner_uid")); // Pass winner UID or other result info
                }

                // Check if opponent submitted (for simpler win conditions) - Adapt as needed
                // String opponentSubmission = snapshot.getString(opponentPlayerKey + "_submission");
                // if (opponentSubmission != null) { /* Opponent finished */ }


            } else {
                Log.w(TAG, "Battle room " + roomId + " deleted or does not exist.");
                handleBattleEnd("Error"); // Room disappeared
            }
        });
    }

    private void loadProblem(String problemId) {
        db.collection("problems").document(problemId)
                .get()
                .addOnSuccessListener(doc -> {
                    if (doc.exists()) {
                        problemTitleText.setText(doc.getString("title"));
                        problemDescriptionText.setText(doc.getString("description"));
                        // Load starter code, test cases etc. if needed for your logic
                    } else {
                        problemTitleText.setText("Error loading problem");
                        Log.w(TAG, "Problem not found: " + problemId);
                    }
                })
                .addOnFailureListener(e -> {
                    problemTitleText.setText("Error loading problem");
                    Log.e(TAG, "Error getting problem: " + problemId, e);
                });
    }

    private void startTimer(long durationMs) {
        battleTimer = new CountDownTimer(durationMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                updateTimerDisplay(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                updateTimerDisplay(0);
                if(!battleFinished) {
                    Log.d(TAG,"Timer finished naturally.");
                    // Decide timeout logic - did anyone submit? Set status?
                    // For simplicity, just mark as finished locally, Firestore listener might get final state
                    handleBattleEnd("Timeout");
                }
            }
        }.start();
    }

    private void updateTimerDisplay(long millisUntilFinished) {
        String timeFormatted = String.format(Locale.getDefault(), "%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished),
                TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) -
                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millisUntilFinished))
        );
        timerText.setText(timeFormatted);
    }


    private void submitCode() {
        if (battleFinished || myPlayerKey == null) return;

        String code = codeInputEditText.getText().toString();
        // --- IMPORTANT ---
        // This is where secure code execution *should* happen on a backend.
        // Here, we'll just simulate a score and update Firestore.
        // Replace this with your actual (potentially insecure client-side) judging logic.
        long calculatedScore = calculateScore(code); // Your scoring logic

        Map<String, Object> updates = new HashMap<>();
        updates.put(myPlayerKey + "_score", calculatedScore);
        updates.put(myPlayerKey + "_submission", code); // Store submitted code
        // Potentially update status if this submission ends the game based on your rules
        // updates.put("status", "finished");
        // updates.put("winner_uid", determineWinner()); // Add complex win logic if needed

        db.collection("battle_rooms").document(roomId)
                .update(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Score updated successfully for " + myPlayerKey);
                    Toast.makeText(this, "Code Submitted!", Toast.LENGTH_SHORT).show();
                    // Maybe disable submit button after successful submission
                    // submitButton.setEnabled(false);
                    // Note: The listener will pick up this change and update the UI score.
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Error updating score", e);
                    Toast.makeText(this, "Submission failed. Try again.", Toast.LENGTH_SHORT).show();
                });
    }

    // !! Replace with your actual scoring logic !!
    // This is a placeholder and likely insecure if run purely on client
    private long calculateScore(String code) {
        // Example: score based on code length (very basic)
        // Or run local tests (insecure)
        // Or check against expected output for fixed inputs (insecure)
        return code.length() * 10L; // Placeholder
    }

    private void handleBattleEnd(String resultInfo) {
        if (battleFinished) return; // Prevent multiple executions
        battleFinished = true;

        Log.d(TAG, "Handling battle end. Info: " + resultInfo);

        // Stop listener and timer
        if (battleStateListener != null) {
            battleStateListener.remove();
        }
        if (battleTimer != null) {
            battleTimer.cancel();
        }

        // Update room status to 'finished' in Firestore if not already done
        // This should ideally be done atomically based on the final condition (timeout, submission etc.)
        // For simplicity, one client might trigger this on timeout/error
        DocumentReference roomRef = db.collection("battle_rooms").document(roomId);
        roomRef.get().addOnSuccessListener(snapshot -> {
            if(snapshot.exists() && !"finished".equals(snapshot.getString("status"))) {
                Map<String, Object> finalUpdates = new HashMap<>();
                finalUpdates.put("status", "finished");
                finalUpdates.put("endTime", new Date());
                // Determine winner based on final scores from snapshot if needed
                // finalUpdates.put("winner_uid", determineWinner(snapshot));
                roomRef.update(finalUpdates).addOnCompleteListener(task -> Log.d(TAG,"Marked room as finished"));
            }
        });


        // Start ResultActivity
        Intent intent = new Intent(BattleActivity.this, ResultActivity.class);
        intent.putExtra("ROOM_ID", roomId);
        intent.putExtra("RESULT_INFO", resultInfo); // Pass simple result string for now
        startActivity(intent);
        finish(); // Close BattleActivity
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up listeners and timers definitely
        if (battleStateListener != null) {
            battleStateListener.remove();
        }
        if (battleTimer != null) {
            battleTimer.cancel();
        }
        // Consider updating room status if user leaves mid-battle (e.g., 'abandoned')
    }
}