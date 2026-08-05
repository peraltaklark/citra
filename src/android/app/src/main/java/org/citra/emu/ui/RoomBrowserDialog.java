package org.citra.emu.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.citra.emu.R;
import org.citra.emu.utils.CitraDirectory;
import org.citra.emu.utils.NetPlayManager;
import org.citra.emu.utils.WebRequestHandler;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

public class RoomBrowserDialog {

    private static String getWebApiUrl() {
        // Default fallback URL
        String defaultUrl = "http://88.198.47.46:5000";
        try {
            File configFile = new File(CitraDirectory.getConfigFile());
            if (!configFile.exists()) {
                return defaultUrl;
            }
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            boolean inWebService = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.equals("[WebService]")) {
                    inWebService = true;
                } else if (line.startsWith("[") && line.endsWith("]")) {
                    inWebService = false;
                } else if (inWebService && line.startsWith("web_api_url")) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) {
                        String url = parts[1].trim();
                        reader.close();
                        return url.isEmpty() ? defaultUrl : url;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // fall through to default
        }
        return defaultUrl;
    }

    public static class RoomInfo {
        String name;
        String owner;
        String address;
        int port;
        String preferredGame;
        int players;
        int maxPlayers;
        boolean hasPassword;

        public RoomInfo(JSONObject json) {
            try {
                this.name = json.optString("name", "Unknown");
                this.owner = json.optString("owner", "Unknown");
                this.address = json.optString("address", "");
                this.port = json.optInt("port", 24872);
                this.preferredGame = json.optString("preferredGameName", "");
                this.maxPlayers = json.optInt("maxPlayers", 4);
                this.hasPassword = json.optBoolean("hasPassword", false);
                JSONArray playersArr = json.optJSONArray("players");
                this.players = playersArr != null ? playersArr.length() : 0;
            } catch (Exception e) {
                this.name = "Unknown";
                this.owner = "Unknown";
                this.address = "";
                this.port = 24872;
                this.preferredGame = "";
                this.maxPlayers = 4;
                this.hasPassword = false;
                this.players = 0;
            }
        }
    }

    public static void ShowRoomBrowser(final Activity activity) {
        View rootView = activity.getLayoutInflater().inflate(R.layout.dialog_room_browser, null);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(rootView)
                .setCancelable(true)
                .create();
        dialog.show();

        RecyclerView recyclerView = rootView.findViewById(R.id.room_list);
        TextView emptyText = rootView.findViewById(R.id.empty_text);
        TextView loadingText = rootView.findViewById(R.id.loading_text);
        Button refreshButton = rootView.findViewById(R.id.btn_refresh);

        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        RoomAdapter adapter = new RoomAdapter(activity, dialog);
        recyclerView.setAdapter(adapter);

        refreshButton.setOnClickListener(v -> {
            loadingText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            emptyText.setVisibility(View.GONE);
            refreshButton.setEnabled(false);
            fetchRooms(adapter, recyclerView, emptyText, loadingText, refreshButton);
        });

        fetchRooms(adapter, recyclerView, emptyText, loadingText, refreshButton);
    }

    private static void fetchRooms(final RoomAdapter adapter, final RecyclerView recyclerView,
                                   final TextView emptyText, final TextView loadingText,
                                   final Button refreshButton) {
        new Thread(() -> {
            List<RoomInfo> rooms = new ArrayList<>();
            try {
                String apiUrl = getWebApiUrl() + "/lobby";
                WebRequestHandler handler = WebRequestHandler.Create(apiUrl);
                if (handler != null) {
                    StringBuilder sb = new StringBuilder();
                    byte[] buffer = handler.data();
                    int bytesRead;
                    while ((bytesRead = handler.read()) > 0) {
                        sb.append(new String(buffer, 0, bytesRead));
                    }
                    handler.close();
                    String response = sb.toString();
                    if (!response.isEmpty()) {
                        JSONObject json = new JSONObject(response);
                        JSONArray roomsArray = json.getJSONArray("rooms");
                        for (int i = 0; i < roomsArray.length(); i++) {
                            rooms.add(new RoomInfo(roomsArray.getJSONObject(i)));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            final List<RoomInfo> finalRooms = rooms;
            new Handler(Looper.getMainLooper()).post(() -> {
                loadingText.setVisibility(View.GONE);
                refreshButton.setEnabled(true);
                if (finalRooms.isEmpty()) {
                    emptyText.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                } else {
                    emptyText.setVisibility(View.GONE);
                    recyclerView.setVisibility(View.VISIBLE);
                    adapter.setRooms(finalRooms);
                }
            });
        }).start();
    }

    private static class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.ViewHolder> {
        private final Activity activity;
        private final AlertDialog dialog;
        private List<RoomInfo> rooms = new ArrayList<>();

        RoomAdapter(Activity activity, AlertDialog dialog) {
            this.activity = activity;
            this.dialog = dialog;
        }

        void setRooms(List<RoomInfo> rooms) {
            this.rooms = rooms;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.list_item_room, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            RoomInfo room = rooms.get(position);
            String lockStr = room.hasPassword ? " \uD83D\uDD12" : "";
            holder.roomName.setText(room.name + lockStr);
            holder.roomOwner.setText("Host: " + room.owner);
            holder.roomGame.setText(room.preferredGame.isEmpty() ? "Any game" : room.preferredGame);
            holder.roomPlayers.setText(room.players + "/" + room.maxPlayers);

            holder.itemView.setAlpha(room.players >= room.maxPlayers ? 0.5f : 1.0f);

            holder.itemView.setOnClickListener(v -> {
                if (room.players >= room.maxPlayers) {
                    Toast.makeText(activity, R.string.multiplayer_room_is_full, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (room.address.isEmpty()) {
                    Toast.makeText(activity, "Room address not available", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (room.hasPassword) {
                    // Prompt for password
                    EditText passwordInput = new EditText(activity);
                    passwordInput.setHint("Password");
                    new AlertDialog.Builder(activity)
                            .setTitle("Password Required")
                            .setMessage("Enter password for \"" + room.name + "\"")
                            .setView(passwordInput)
                            .setPositiveButton("Join", (d, which) -> {
                                String password = passwordInput.getText().toString();
                                joinRoom(activity, dialog, room, password);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    joinRoom(activity, dialog, room, "");
                }
            });
        }

        private void joinRoom(Activity activity, AlertDialog dialog, RoomInfo room, String password) {
            String username = NetPlayManager.GetUsername(activity);
            if (NetPlayManager.NetPlayJoinRoom(room.address, room.port, username, password) == 0) {
                Toast.makeText(activity, R.string.multiplayer_join_room_success, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } else {
                Toast.makeText(activity, R.string.multiplayer_join_room_failed, Toast.LENGTH_SHORT).show();
            }
        }

        @Override
        public int getItemCount() {
            return rooms.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView roomName, roomOwner, roomGame, roomPlayers;
            ViewHolder(View itemView) {
                super(itemView);
                roomName = itemView.findViewById(R.id.room_name);
                roomOwner = itemView.findViewById(R.id.room_owner);
                roomGame = itemView.findViewById(R.id.room_game);
                roomPlayers = itemView.findViewById(R.id.room_players);
            }
        }
    }
}
