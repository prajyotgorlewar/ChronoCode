package com.example.chronocode;// ResultActivity.java
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.WriteBatch;

public class ResultActivity extends AppCompatActivity {

    private static final String TAG = "ResultActivity";

    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;

    private String roomId;
    private String resultInfo; // Simple result info passed from BattleActivity

    private TextView resultText, scoreDetailsText;
    private Button backToMainButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_result); // Ensure you have activity_result.xml

        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();

        roomId = getIntent().getStringExtra("ROOM_ID");
        resultInfo = getIntent().getStringExtra("RESULT_INFO");

        resultText = findViewById(R.id.resultText);
        scoreDetailsText = findViewById(R.id.scoreDetailsText);
        backToMainButton = findViewById(R.id.backToMainButton);

        backToMainButton.setOnClickListener(v -> {
            Intent intent = new Intent(ResultActivity.this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        });

        if (roomId == null || currentUser == null) {
            resultText.setText("Error displaying results.");
            Toast.makeText(this, "Could not load results.", Toast.LENGTH_SHORT).show();
            return;
        }

        loadResults();
    }

    private void loadResults() {
        DocumentReference roomRef = db.collection("battle_rooms").document(roomId);
        roomRef.get().addOnCompleteListener(task -> {
            if (task.isSuccessful() && task.getResult() != null && task.getResult().exists()) {
                DocumentSnapshot roomData = task.getResult();
                displayResults(roomData);
                // Update player ratings after displaying results
                updateRatings(roomData);
            } else {
                Log.w(TAG, "Error loading results for room: " + roomId, task.getException());
                resultText.setText("Could not load results.");
                scoreDetailsText.setText("Error: " + (task.getException() != null ? task.getException().getMessage() : "Unknown"));
            }
        });
    }

    private void displayResults(DocumentSnapshot roomData) {
        String player1Uid = roomData.getString("player1_uid");
        String player2Uid = roomData.getString("player2_uid");
        Long player1Score = roomData.getLong("player1_score");
        Long player2Score = roomData.getLong("player2_score");
        String p1Name = roomData.getString("player1_displayName");
        String p2Name = roomData.getString("player2_displayName");

        player1Score = (player1Score == null) ? 0 : player1Score;
        player2Score = (player2Score == null) ? 0 : player2Score;
        p1Name = (p1Name == null) ? "Player 1" : p1Name;
        p2Name = (p2Name == null) ? "Player 2" : p2Name;


        String winnerUid = determineWinner(roomData); // Use helper
        String resultString;

        if (winnerUid == null) {
            resultString = "It's a Draw!";
        } else if (winnerUid.equals(currentUser.getUid())) {
            resultString = "You Won!";
        } else {
            resultString = "You Lost!";
        }

        // Add reason if timeout or error occurred (using resultInfo)
        if("Timeout".equals(resultInfo)) {
            resultString += " (Timeout)";
        } else if("Error".equals(resultInfo)) {
            resultString = "Battle ended due to error.";
        }


        resultText.setText(resultString);
        scoreDetailsText.setText(String.format("%s Score: %d\n%s Score: %d",
                p1Name, player1Score, p2Name, player2Score));
    }


    // Helper to determine winner based on scores
    private String determineWinner(DocumentSnapshot roomData) {
        Long player1Score = roomData.getLong("player1_score");
        Long player2Score = roomData.getLong("player2_score");
        player1Score = (player1Score == null) ? 0 : player1Score;
        player2Score = (player2Score == null) ? 0 : player2Score;

        if (player1Score > player2Score) {
            return roomData.getString("player1_uid");
        } else if (player2Score > player1Score) {
            return roomData.getString("player2_uid");
        } else {
            return null; // Draw
        }
    }


    private void updateRatings(DocumentSnapshot roomData) {
        String player1Uid = roomData.getString("player1_uid");
        String player2Uid = roomData.getString("player2_uid");
        String winnerUid = determineWinner(roomData);

        if (player1Uid == null || player2Uid == null) {
            Log.w(TAG, "Cannot update ratings, player UIDs missing in room " + roomId);
            return;
        }

        DocumentReference p1Ref = db.collection("users").document(player1Uid);
        DocumentReference p2Ref = db.collection("users").document(player2Uid);

        db.runTransaction(transaction -> {
            DocumentSnapshot p1Snap = transaction.get(p1Ref);
            DocumentSnapshot p2Snap = transaction.get(p2Ref);

            if (!p1Snap.exists() || !p2Snap.exists()) {
                throw new FirebaseFirestoreException("Player profile not found",
                        FirebaseFirestoreException.Code.ABORTED);
            }

            long p1Rating = p1Snap.getLong("rating") != null ? p1Snap.getLong("rating") : 1000;
            long p2Rating = p2Snap.getLong("rating") != null ? p2Snap.getLong("rating") : 1000;

            // --- Simple Rating Update Logic (Placeholder) ---
            // Replace with a proper Elo or similar rating system calculation
            final int RATING_CHANGE = 15;
            long newP1Rating = p1Rating;
            long newP2Rating = p2Rating;

            if (winnerUid == null) { // Draw
                // No change, or maybe +/- 0 or small change
            } else if (winnerUid.equals(player1Uid)) { // Player 1 won
                newP1Rating += RATING_CHANGE;
                newP2Rating -= RATING_CHANGE;
            } else { // Player 2 won
                newP1Rating -= RATING_CHANGE;
                newP2Rating += RATING_CHANGE;
            }
            // Ensure ratings don't go below a minimum (e.g., 0 or 100)
            newP1Rating = Math.max(100, newP1Rating);
            newP2Rating = Math.max(100, newP2Rating);
            // --- End Placeholder Logic ---


            transaction.update(p1Ref, "rating", newP1Rating);
            transaction.update(p2Ref, "rating", newP2Rating);

            // Optionally update matches played, wins/losses
            // transaction.update(p1Ref, "matchesPlayed", FieldValue.increment(1));
            // transaction.update(p2Ref, "matchesPlayed", FieldValue.increment(1));
            // if(winnerUid != null) transaction.update(db.collection("users").document(winnerUid), "wins", FieldValue.increment(1));


            return null; // Transaction success
        }).addOnSuccessListener(aVoid -> {
            Log.d(TAG, "Ratings updated successfully for room " + roomId);
            Toast.makeText(this, "Ratings updated!", Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e -> {
            Log.w(TAG, "Rating update transaction failed for room " + roomId, e);
            Toast.makeText(this, "Failed to update ratings.", Toast.LENGTH_SHORT).show();
        });
    }
}