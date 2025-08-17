// MusicManager.java

import java.util.ArrayList;
import java.util.List;

public class MusicManager {
    private List<String> songs;

    public MusicManager() {
        songs = new ArrayList<>();
    }

    public void addSong(String song) {
        // Agregar una canción a la lista
        songs.add(song);
        System.out.println("🎶 Canción añadida: " + song);
    }

    public void playMusic() {
        if (songs.isEmpty()) {
            System.out.println("No hay canciones para reproducir.");
            return;
        }
        System.out.println("Reproduciendo música...");
        for (String song : songs) {
            System.out.println("🎵 Reproduciendo: " + song);
        }
    }
}