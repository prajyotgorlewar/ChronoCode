package com.example.chronocode;

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
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private FirebaseUser currentUser;

    private Button findBattleButton;
    private TextView userRatingText; // To display user rating

    private ListenerRegistration matchmakingListener; // To listen for opponent joining

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Ensure you have activity_main.xml

        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        findBattleButton = findViewById(R.id.findBattleButton); // Add button in XML
        userRatingText = findViewById(R.id.userRatingText); // Add TextView in XML

        findBattleButton.setOnClickListener(v -> findBattle());
    }

    @Override
    public void onStart() {
        super.onStart();
        currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            signInAnonymously();
        } else {
            Log.d(TAG, "User signed in: " + currentUser.getUid());
            loadUserProfile();
            findBattleButton.setEnabled(true);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Remove listener when activity is not visible
        if (matchmakingListener != null) {
            matchmakingListener.remove();
            matchmakingListener = null;
        }
    }

    private void signInAnonymously() {
        mAuth.signInAnonymously()
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "signInAnonymously:success");
                        currentUser = mAuth.getCurrentUser();
                        createUserProfileIfNotExists(); // Create profile for new anonymous user
                        loadUserProfile();
                        findBattleButton.setEnabled(true);
                    } else {
                        Log.w(TAG, "signInAnonymously:failure", task.getException());
                        Toast.makeText(MainActivity.this, "Authentication failed.",
                                Toast.LENGTH_SHORT).show();
                        findBattleButton.setEnabled(false);
                    }
                });
    }

    private void createUserProfileIfNotExists() {
        if (currentUser != null) {
            DocumentReference userRef = db.collection("users").document(currentUser.getUid());
            userRef.get().addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    DocumentSnapshot document = task.getResult();
                    if (document != null && !document.exists()) {
                        // Document does not exist, create it
                        Map<String, Object> newUser = new HashMap<>();
                        newUser.put("displayName", "User_" + currentUser.getUid().substring(0, 6)); // Or ask user
                        newUser.put("rating", 1000); // Default rating
                        newUser.put("createdAt", new Date());

                        userRef.set(newUser)
                                .addOnSuccessListener(aVoid -> Log.d(TAG, "User profile created for " + currentUser.getUid()))
                                .addOnFailureListener(e -> Log.w(TAG, "Error creating user profile", e));
                    } else if (document != null) {
                        Log.d(TAG, "User profile already exists for " + currentUser.getUid());
                    }
                } else {
                    Log.w(TAG, "Error checking user profile", task.getException());
                }
            });
        }
    }

    private void loadUserProfile() {
        if (currentUser != null) {
            db.collection("users").document(currentUser.getUid())
                    .get()
                    .addOnSuccessListener(documentSnapshot -> {
                        if (documentSnapshot.exists()) {
                            Long rating = documentSnapshot.getLong("rating");
                            if (rating != null) {
                                userRatingText.setText("Rating: " + rating);
                            } else {
                                userRatingText.setText("Rating: N/A");
                            }
                            // You can load other profile info here
                        } else {
                            Log.d(TAG,"User profile not found");
                            userRatingText.setText("Rating: N/A");
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Error loading user profile", e);
                        Toast.makeText(this, "Failed to load profile.", Toast.LENGTH_SHORT).show();
                        userRatingText.setText("Rating: Error");
                    });
        }
    }

    private void findBattle() {
        if (currentUser == null) {
            Toast.makeText(this, "You must be signed in.", Toast.LENGTH_SHORT).show();
            return;
        }

        findBattleButton.setEnabled(false); // Prevent multiple clicks
        Toast.makeText(this, "Searching for opponent...", Toast.LENGTH_SHORT).show();

        // 1. Query for an existing "waiting" battle room
        db.collection("battle_rooms")
                .whereEqualTo("status", "waiting")
                .whereNotEqualTo("player1_uid", currentUser.getUid()) // Don't join your own room
                .orderBy("createdAt", Query.Direction.ASCENDING) // Join oldest waiting room
                .limit(1)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && !task.getResult().isEmpty()) {
                        // Found a waiting room - Join it
                        DocumentSnapshot roomSnapshot = task.getResult().getDocuments().get(0);
                        String roomId = roomSnapshot.getId();
                        joinBattleRoom(roomId, roomSnapshot.getString("player1_displayName"));
                    } else if (task.isSuccessful()) {
                        // No waiting rooms found - Create a new one
                        createBattleRoom();
                    } else {
                        Log.w(TAG, "Error finding battle room.", task.getException());
                        Toast.makeText(this, "Error finding match. Try again.", Toast.LENGTH_SHORT).show();
                        findBattleButton.setEnabled(true);
                    }
                });
    }

    private void joinBattleRoom(String roomId, String opponentName) {
        Log.d(TAG, "Joining room: " + roomId);
        DocumentReference roomRef = db.collection("battle_rooms").document(roomId);
        DocumentReference userRef = db.collection("users").document(currentUser.getUid());

        // Get current user's display name
        userRef.get().addOnSuccessListener(userDoc -> {
            if (userDoc.exists()) {
                final String myDisplayName = userDoc.getString("displayName");

                // Update the battle room
                Map<String, Object> updates = new HashMap<>();
                updates.put("player2_uid", currentUser.getUid());
                updates.put("player2_displayName", myDisplayName != null ? myDisplayName : "Player 2");
                updates.put("status", "ongoing"); // Room is now full and ongoing
                updates.put("startTime", new Date()); // Mark start time

                roomRef.update(updates)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "Successfully joined room: " + roomId);
                            // Room joined, opponent found - Start BattleActivity
                            startBattleActivity(roomId, opponentName); // opponentName is player1's name
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "Failed to join room: " + roomId, e);
                            Toast.makeText(MainActivity.this, "Failed to join match. Try again.", Toast.LENGTH_SHORT).show();
                            findBattleButton.setEnabled(true);
                        });
            } else {
                Log.e(TAG, "User document does not exist");
                Toast.makeText(MainActivity.this, "Failed to join match. Try again.", Toast.LENGTH_SHORT).show();
                findBattleButton.setEnabled(true);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "Failed to get user display name for joining room", e);
            Toast.makeText(MainActivity.this, "Failed to join match. Try again.", Toast.LENGTH_SHORT).show();
            findBattleButton.setEnabled(true);
        });
    }

    private void createBattleRoom() {
        Log.d(TAG, "Creating new battle room");
        DocumentReference userRef = db.collection("users").document(currentUser.getUid());

        userRef.get().addOnCompleteListener(userTask -> {
            if (userTask.isSuccessful()) {
                DocumentSnapshot userDoc = userTask.getResult();
                final String myDisplayName;

                if (userDoc != null && userDoc.exists() && userDoc.getString("displayName") != null) {
                    myDisplayName = userDoc.getString("displayName");
                } else {
                    myDisplayName = "Player 1"; // Default
                    Log.w(TAG, "User document does not exist or missing display name");
                    createUserProfileIfNotExists();
                }

                Map<String, Object> newRoom = new HashMap<>();
                newRoom.put("player1_uid", currentUser.getUid());
                newRoom.put("player1_displayName", myDisplayName);
                newRoom.put("player2_uid", null); // Waiting for player 2
                newRoom.put("player2_displayName", null);
                newRoom.put("status", "waiting"); // Initial status
                newRoom.put("createdAt", new Date());
                newRoom.put("problemId", getRandomProblemId()); // Use a helper method
                newRoom.put("player1_score", 0);
                newRoom.put("player2_score", 0);

                db.collection("battle_rooms")
                        .add(newRoom) // Firestore generates a unique ID
                        .addOnSuccessListener(documentReference -> {
                            String roomId = documentReference.getId();
                            Log.d(TAG, "Created battle room: " + roomId);
                            Toast.makeText(MainActivity.this, "Waiting for opponent...", Toast.LENGTH_LONG).show();
                            // Listen for player 2 joining this specific room
                            listenForOpponent(roomId, myDisplayName);
                        })
                        .addOnFailureListener(e -> {
                            Log.w(TAG, "Error creating battle room", e);
                            Toast.makeText(MainActivity.this, "Error creating match. Try again.", Toast.LENGTH_SHORT).show();
                            findBattleButton.setEnabled(true);
                        });
            } else {
                Log.e(TAG, "Failed to get user document", userTask.getException());
                Toast.makeText(MainActivity.this, "Error creating match. Try again.", Toast.LENGTH_SHORT).show();
                findBattleButton.setEnabled(true);
            }
        });
    }

    private String getRandomProblemId() {
        // In a real app, you'd likely fetch a list of problem IDs from Firestore
        // and select one randomly. For this example, we'll keep it simple.
        int problemCount = 5; // Replace with the actual number of problems
        int randomProblemNumber = (int) (Math.random() * problemCount) + 1;
        return "problem_" + randomProblemNumber;
    }

    private void listenForOpponent(String roomId, final String myName) {
        // Remove previous listener if any
        if (matchmakingListener != null) {
            matchmakingListener.remove();
        }

        DocumentReference roomRef = db.collection("battle_rooms").document(roomId);
        matchmakingListener = roomRef.addSnapshotListener((snapshot, e) -> {
            if (e != null) {
                Log.w(TAG, "Listen failed.", e);
                // Consider canceling the room if listen fails badly? Or just inform user.
                Toast.makeText(this, "Connection error. Try again.", Toast.LENGTH_SHORT).show();
                findBattleButton.setEnabled(true); // Allow retry
                if (matchmakingListener != null) matchmakingListener.remove();
                // Maybe delete the waiting room if listen fails?
                // roomRef.delete();
                return;
            }

            if (snapshot != null && snapshot.exists()) {
                String status = snapshot.getString("status");
                String player2Uid = snapshot.getString("player2_uid");
                String player2Name = snapshot.getString("player2_displayName");

                if ("ongoing".equals(status) && player2Uid != null) {
                    // Opponent found and joined! Stop listening and start battle.
                    Log.d(TAG, "Opponent joined room: " + roomId);
                    Toast.makeText(this, "Opponent found!", Toast.LENGTH_SHORT).show();
                    if (matchmakingListener != null) {
                        matchmakingListener.remove(); // Stop listening now
                        matchmakingListener = null;
                    }
                    startBattleActivity(roomId, player2Name != null ? player2Name : "Player 2");
                } else if ("waiting".equals(status)) {
                    // Still waiting, do nothing here, Toast was shown before.
                    Log.d(TAG,"Still waiting in room " + roomId);
                } else if("canceled".equals(status) || "finished".equals(status)) {
                    // Room was canceled or somehow finished before opponent joined?
                    Log.w(TAG, "Room " + roomId + " is no longer waiting. Status: " + status);
                    Toast.makeText(this, "Matchmaking canceled or expired.", Toast.LENGTH_SHORT).show();
                    findBattleButton.setEnabled(true);
                    if (matchmakingListener != null) matchmakingListener.remove();
                }
            } else {
                // Room was deleted?
                Log.w(TAG, "Room " + roomId + " snapshot is null or doesn't exist.");
                Toast.makeText(this, "Matchmaking canceled.", Toast.LENGTH_SHORT).show();
                findBattleButton.setEnabled(true);
                if (matchmakingListener != null) matchmakingListener.remove();
            }
        });
    }

    private void startBattleActivity(String roomId, String opponentDisplayName) {
        Intent intent = new Intent(MainActivity.this, BattleActivity.class);
        intent.putExtra("ROOM_ID", roomId);
        intent.putExtra("OPPONENT_NAME", opponentDisplayName);
        startActivity(intent);
    }
}