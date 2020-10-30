package redempt.youtubetospofy;

import redempt.youtubetospofy.json.JSONList;
import redempt.youtubetospofy.json.JSONMap;
import redempt.youtubetospofy.json.JSONParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class YoutubeToSpofy {
	
	private static String playlistItemsRequest = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId={playlist}&key={key}";
	private static String spotifySearch = "https://api.spotify.com/v1/search?q={trackName}&type=track&limit=1";
	private static String spotifyAdd = "https://api.spotify.com/v1/playlists/{playlist_id}/tracks";
	private static HttpClient client = HttpClient.newHttpClient();
	
	public static void main(String[] args) throws InterruptedException {
		System.out.println("A Youtube API key is needed to run this program.");
		System.out.println("If you don't know how to get one, follow this link: https://rapidapi.com/blog/how-to-get-youtube-api-key/");
		System.out.println("Please enter your Youtube API key:");
		Scanner scanner = new Scanner(System.in);
		String key = scanner.nextLine();
		System.out.println("Please enter the Youtube playlist ID - if the link to the playlist is https://www.youtube.com/playlist?list=ABCD1234, the playlist ID is ABCD1234");
		String youtubePlaylist = scanner.nextLine();
		System.out.println("Fetching playlist data from YouTube...");
		List<String> songs = new ArrayList<>();
		String pageToken = null;
		do {
			String body = getPlaylistItems(youtubePlaylist, pageToken, key);
			JSONMap map = JSONParser.parseMap(body);
			pageToken = map.getString("nextPageToken");
			map.getList("items").cast(JSONMap.class).forEach(m -> {
				String songName = m.getMap("snippet").getString("title");
				songs.add(songName);
			});
		} while (pageToken != null);
		System.out.println("Got " + songs.size() + " songs from YouTube playlist.");
		System.out.println("To continue, you will need a Spotify developer OAuth code.");
		System.out.println("Follow these directions to create an app on the Spotify Developer Dashboard: https://developer.spotify.com/documentation/general/guides/app-settings/#register-your-app");
		System.out.println("Once you've created your app, go to Console -> Playlists -> Add Items and click Get Token. You will need to grant playlist-modify-public and playlist-modify-private to this token.");
		System.out.println("Paste the token below:");
		String spotifyToken = scanner.nextLine();
		System.out.println("Searching Spotify for songs...");
		Queue<String> ids = new ArrayDeque<>();
		for (int i = 0; i < songs.size(); i++) {
			String song = songs.get(i);
			String body = searchSpofy(song, spotifyToken);
			JSONMap map = JSONParser.parseMap(body);
			if (map == null || map.getMap("tracks") == null) {
				System.out.println("Error searching for track: " + song);
				if (map.getMap("error") == null) {
					continue;
				}
				int status = map.getMap("error").getInt("status");
				if (status == 429) {
					System.out.println("Rate limit reached, retrying in 5 seconds...");
					i--;
					Thread.sleep(5000);
					continue;
				}
				if (status == 401) {
					System.out.println("Invalid token, cannot continue.");
					System.exit(1);
				}
				continue;
			}
			JSONList items = map.getMap("tracks").getList("items");
			if (items.size() == 0) {
				System.out.println("Could not find track on Spotify: " + song);
				continue;
			}
			String id = items.getMap(0).getMap("external_urls").getString("spotify");
			id = id.substring(id.lastIndexOf('/') + 1);
			System.out.println(id + ": " + song);
			ids.add(id);
		}
		System.out.println("Found " + ids.size() + " songs on Spotify (accuracy is not 100% guaranteed for found songs, manual pruning will likely be necessary)");
		System.out.println("Please enter Spotify Playlist ID to add songs to:");
		String playlistId = scanner.nextLine();
		System.out.println("Attempting to add tracks to playlist...");
		while (ids.size() > 0) {
			int count = addTracks(ids, playlistId, spotifyToken);
			System.out.println(count + " songs added...");
		}
		System.out.println("Done");
	}
	
	private static String getPlaylistItems(String playlist, String page, String key) {
		try {
			String loc = playlistItemsRequest
					.replace("{playlist}", playlist)
					.replace("{key}", key)
					+ (page == null ? "" : "&pageToken=" + page);
			HttpRequest req = HttpRequest.newBuilder().uri(new URI(loc)).build();
			HttpResponse<String> response = client.send(req, BodyHandlers.ofString());
			return response.body();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private static String stripSongName(String songName) {
		int last = Math.max(songName.lastIndexOf(')'), songName.lastIndexOf(']'));
		if (last != -1  && last > songName.length() / 2) {
			songName = songName.substring(0, last + 1);
		}
		return songName.split("\\|")[0].replaceAll("(?i)[\\(\\[].+?(?<!remix|cover)[\\)\\]]", "")
				.replaceAll("[^a-zA-Z0-9 ]", "").replace(" x ", " ").replaceAll("f(ea)?t\\.?", "").trim();
	}
	
	private static String searchSpofy(String trackName, String key) {
		try {
			String loc = spotifySearch.replace("{trackName}", URLEncoder.encode(stripSongName(trackName), StandardCharsets.UTF_8));
			HttpRequest req = HttpRequest.newBuilder().uri(new URI(loc)).header("Authorization", "Bearer " + key).build();
			HttpResponse<String> response = client.send(req, BodyHandlers.ofString());
			return response.body();
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private static int addTracks(Queue<String> ids, String playlistId, String key) {
		try {
			String loc = spotifyAdd.replace("{playlist_id}", playlistId);
			JSONList list = new JSONList();
			int i = 0;
			for (i = 0; i < 100 && ids.size() > 0; i++) {
				String id = "spotify:track:" + ids.poll();
				list.add(id);
			}
			ids.forEach(s -> list.add("spotify:track:" + s));
			JSONMap map = new JSONMap();
			map.put("uris", list);
			HttpRequest req = HttpRequest.newBuilder().uri(new URI(loc)).header("Authorization", "Bearer " + key)
					.POST(HttpRequest.BodyPublishers.ofString(map.toString())).build();
			HttpResponse<String> response = client.send(req, BodyHandlers.ofString());
			return i;
		} catch (Exception e) {
			e.printStackTrace();
			return 0;
		}
	}
	
}
