import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.log4j.Logger;
import org.apache.log4j.xml.DOMConfigurator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import groovy.transform.Field

@Field Logger logger = Logger.getLogger("YT");
// data info - sitename
@Field String site = "中天新聞";
@Field String jobtxdate = "2019-01-01";
@Field List <String> keyList = new ArrayList <> ();
@Field int keyIndex = 0;
@Field DateFormat jobtxDf = new SimpleDateFormat("yyyy-MM-dd");
@Field DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
@Field String runUrl;

// data replace regex tools
@Field firstRuleString = "【民視即時新聞】,#三立iNEWS,●訂閱頻道，最新資訊馬上接收→http://bit.ly/2flUkiY";
@Field endRuleString = "中天新聞24小時直播：,更多影片在Go車誌官網：,【全新東森新聞APP】,請鎖定《udn.com聯合新聞網》,聯合影音》https://video.udn.com/";

// load log4j
DOMConfigurator.configure("C:/log4j.xml");
try {
	getApiKey()
	Collections.shuffle(keyList);
	String srcUrl = "https://www.youtube.com/channel/UCpu3bemTQwAU8PqM4kJdoEQ";    // the channel you want to access
	start(srcUrl);
} catch (Exception e) {
	logger.error(getStackTrace(e));
}

// load your youtube api user key, if you have more than 1 key, that woulb be great :)
def void getApiKey() {
	String keyFilePath = "C:/YOUTUBE_API_Key.txt";
	File keyFile = new File(keyFilePath);

	try {
		BufferedReader bf = new BufferedReader(new InputStreamReader(new FileInputStream(keyFile), "UTF8"));
		String str;
		while ((str = bf.readLine()) != null) {
			keyList.add(str)
		}
	} catch (Exception e) {
		logger.error(getStackTrace(e));
	}
}


def void start(String srcUrl) {
	String playlistID = getPlayListID(srcUrl);
	//  logger.info("playlistID: "+playlistID);
	if (playlistID == null){
		return;
	}
	getVideoData(playlistID);
}

// get video list
def void getVideoData(String playlistID) {
	String pageToken = "";
	boolean flag = false;
	String playlistPageUrl = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + playlistID + "&maxResults=50" + "&pageToken=" + pageToken + "&key=";
	Document doc = getDoc(playlistPageUrl);
	if (doc == null){
		return;
	}
	JSONObject pageJSON = new JSONObject();
	try {
		pageJSON = new JSONObject(cleanJSON(doc.select("body").text()));
	} catch (Exception e) {
		logger.info("transform bigPage to JSON failed. url -> " + runUrl);
		//logger.error(getStackTrace(e));
		return;
	}
	JSONArray pageArray = pageJSON.optJSONArray("items");
	for (int i = 0; i < pageArray.length(); i++) {
		JSONObject item = pageArray.optJSONObject(i);
		flag = getData(item);
		if (flag) {
			return;
		}
	}

	if (pageJSON.has("nextPageToken") && !flag) {
		pageToken = pageJSON.optString("nextPageToken");
		recursive(playlistID, pageToken);
	}

}

def void recursive(String playlistID, String pageToken) {
	boolean flag = false;
	String playlistPageUrl = "https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&playlistId=" + playlistID + "&maxResults=50" + "&pageToken=" + pageToken + "&key=";
	Document doc = getDoc(playlistPageUrl);
	if (doc == null){
		return;
	}
	JSONObject pageJSON = new JSONObject();
	try {
		pageJSON = new JSONObject(cleanJSON(doc.select("body").text()));
	} catch (Exception e) {
		logger.info("(recursive)transform bigPage to JSON failed. url -> " + runUrl);
		//logger.error(getStackTrace(e));
		return;
	}
	JSONArray pageArray = pageJSON.optJSONArray("items");
	for (int i = 0; i < pageArray.length(); i++) {
		JSONObject item = pageArray.optJSONObject(i);
		flag = getData(item);
		if (flag) {
			return;
		}
	}

	if (pageJSON.has("nextPageToken") && !flag) {
		pageToken = pageJSON.optString("nextPageToken");
		recursive(playlistID, pageToken);
	}

}

// main video data
def boolean getData(JSONObject vObj) {
	boolean flag = false;
	try {
		JSONObject snippet = vObj.optJSONObject("snippet");
		String videoID = snippet.optJSONObject("resourceId").optString("videoId");
		String pageurl = "https://www.youtube.com/watch?v=" + videoID;
		String title = snippet.optString("title");
		String author = snippet.optString("channelTitle");
		String content = cleanArticleContent(snippet.optString("description"));
		Date publishDate = dateFormat(snippet.optString("publishedAt"));

		String replyUrl = "https://www.googleapis.com/youtube/v3/commentThreads?part=snippet&videoId=" + videoID + "&maxResults=100" + "&key=";

		flag = checkReply(replyUrl, title);
		if (checkTime(publishDate) || flag) {
			flag = false;
		} else {
			flag = true;
		}

		if (checkTime(publishDate)) {
			HashMap output = save(videoID, site, videoID, null, pageurl, title, author, content, null,
					new Timestamp(publishDate.getTime()));
			//outputList.add(output);

		}
	} catch (Exception e) {
		logger.info("getData failed.");
		logger.error(getStackTrace(e));
	}

	return flag;
}

def boolean checkReply(String url, String title) {
	boolean flag = false;
	Document doc = getDoc(url);
	if (doc == null){
		return false;
	}
	JSONObject replyJSON = new JSONObject();
	try {
		replyJSON = new JSONObject(cleanJSON(doc.select("body").text()));
	} catch (Exception e) {
		logger.info("parse reply to JSON failed. url -> " + runUrl);
		//logger.error(getStackTrace(e));
		return false;
	}
	JSONArray replyTemp = replyJSON.optJSONArray("items");
	if (replyTemp.length() == 0) {
		return false;
	}

	String lastReplyTime = replyTemp.optJSONObject(0).optJSONObject("snippet").optJSONObject("topLevelComment").optJSONObject("snippet").optString("publishedAt");
	if (checkTime(dateFormat(lastReplyTime))) {
		for (int i = 0; i < replyTemp.length(); i++) {
			getReply(replyTemp.optJSONObject(i), title);
		}

	} else {
		flag = true;
	}
	return flag;

}

// get the reply of comment
def void getReply(JSONObject input, String title) {
	try {
		JSONObject reply = input.optJSONObject("snippet");
		String replyID = reply.optJSONObject("topLevelComment").optString("id");
		String videoID = reply.optString("videoId");
		String pageurl = "https://www.youtube.com/watch?v=" + videoID;
		String replyAuthor = reply.optJSONObject("topLevelComment").optJSONObject("snippet")
				.optString("authorDisplayName");
		String replyContent = reply.optJSONObject("topLevelComment").optJSONObject("snippet")
				.optString("textDisplay");
		int replyLikeCount = reply.optJSONObject("topLevelComment").optJSONObject("snippet").optInt("likeCount");
		Date replyDate = dateFormat(
				reply.optJSONObject("topLevelComment").optJSONObject("snippet").optString("updatedAt"));;
		if (hasComment(reply)) {
			getComment(replyID, title, videoID, pageurl);
		}

		if (checkTime(replyDate)) {
			HashMap output = save(replyID, site, videoID, videoID, pageurl, title, replyAuthor, replyContent, replyLikeCount, new Timestamp(replyDate.getTime()));
			//outputList.add(output);

		} else {
			return;
		}

	} catch (Exception e) {
		logger.info("analysis reply JSON failed.");
		logger.error(getStackTrace(e));
		return;
	}
}


def void getComment(String pid, String title, String videoID, String pageurl) {
	boolean hasNext = true;
	String commentToken = "";
	while (hasNext) {
		String commentUrl = "https://www.googleapis.com/youtube/v3/comments?part=snippet" + "&parentId=" + pid + "&maxResults=100" + "&pageToken=" + commentToken + "&key=";
		Document doc = getDoc(commentUrl);
		if (doc == null){
			return;
		}
		JSONObject commentJSON = new JSONObject();
		try {
			commentJSON = new JSONObject(cleanJSON(doc.select("body").text()));
		} catch (Exception e) {
			logger.info("transform comment to JSON failed. url-> " + runUrl);
			//logger.error(getStackTrace(e));
			return;
		}

		JSONArray commentArray = commentJSON.optJSONArray("items");
		for (int i = 0; i < commentArray.length(); i++) {
			try {
				JSONObject comment = commentArray.optJSONObject(i);
				String commentID = comment.optString("id");
				String commentAuthor = comment.optJSONObject("snippet").optString("authorDisplayName");
				String commentContent = comment.optJSONObject("snippet").optString("textOriginal");
				int commentLikeCount = comment.optJSONObject("snippet").optInt("likeCount");
				Date commentDate = dateFormat(comment.optJSONObject("snippet").optString("updatedAt"));

				if (checkTime(commentDate)) {
					HashMap output = save(commentID, site, videoID, pid, pageurl, title, commentAuthor, commentContent, commentLikeCount, new Timestamp(commentDate.getTime()));
					//outputList.add(output);

				} else {
					return;
				}
			} catch (Exception e) {
				logger.info("analysis comment JSON failed.");
				//logger.error(getStackTrace(e));
			}
		}

		if (commentJSON.has("nextPageToken")) {
			commentToken = commentJSON.optString("nextPageToken");
		} else {
			hasNext = false;
		}
	}

}

def String getPlayListID(String srcUrl) {
	String playlistID = null;
	String channelUrl = "";
	String[] urlType = null;
	try {
		urlType = new URL(srcUrl).getFile().split("/");
	} catch (MalformedURLException e1) {
		e1.printStackTrace();
	}
	if (urlType[1].equals("channel")) {
		channelUrl = "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&id=" + urlType[2] + "&key=";
	} else if (urlType[1].equals("user")) {
		channelUrl = "https://www.googleapis.com/youtube/v3/channels?part=contentDetails&forUsername=" + urlType[2] + "&key=";
	}
	// if find new type of url, you can add new method
	Document doc = getDoc(channelUrl);
	if (doc == null){
		return null;
	}
	try {
		JSONArray channelJArray = new JSONObject(doc.select("body").text()).getJSONArray("items");
		if (channelJArray.length() != 0) {
			JSONObject obj1 = channelJArray.getJSONObject(0);
			playlistID = obj1.getJSONObject("contentDetails").getJSONObject("relatedPlaylists")
					.getString("uploads");
		}
	} catch (Exception e) {
		e.printStackTrace();
	}
	return playlistID;

}

def boolean hasComment(JSONObject input) {
	boolean result = false;
	if (input.optInt("totalReplyCount") != 0)
		result = true;

	return result;
}


def HashMap save(String postid, String site, String rid, String pid, String pageurl, String postTitle, String authorName, String content, Integer likeCount, Timestamp articleDate) {
	HashMap result = new HashMap();
	result.put("postid", MD5(postid));
	result.put("site", site);
	result.put("rid", MD5(rid));
	result.put("pid", MD5(pid));
	result.put("pageurl", pageurl);
	result.put("postTitle", postTitle);
	result.put("authorName", authorName);
	result.put("content", content);
	result.put("likeCount", likeCount);
	result.put("articleDate", articleDate);

	return result;
}

def String cleanJSON(String input) {
	input = input.replace("\n", "");
	input = input.replace("\" ,", "∫ ,");
	input = input.replace("\",", "∫,");
	input = input.replace(", \"", ", ∫");
	input = input.replace(",\"", ",∫");
	input = input.replace("\" :", "∫ :");
	input = input.replace("\":", "∫:");
	input = input.replace(": \"", ": ∫");
	input = input.replace(":\"", ":∫");
	input = input.replace("{\"", "{∫");
	input = input.replace("{ \"", "{ ∫");
	input = input.replace("\" }", "∫ }");
	input = input.replace("\"}", "∫}");
	input = input.replace("\\", "");
	input = input.replace("\"", "");
	input = input.replace("∫", "\"");
	return input;
}

def Date dateFormat(String input) {
	Date result = new Date();
	try {
		if (input.length() == 24) {
			result = df.parse(input);
		} else if (input.length() == 10) {
			result = jobtxDf.parse(input);
		}
	} catch (Exception e) {
		logger.error(getStackTrace(e));
	}
	return result;
}

def String cleanArticleContent(String content) {
	List < String > firstRule = new ArrayList < > ();
	List < String > endRule = new ArrayList < > ();
	if (!firstRuleString.equals(""))
		firstRule = Arrays.asList(firstRuleString.split(","));
	if (!endRuleString.equals(""))
		endRule = Arrays.asList(endRuleString.split(","));

	for (String rule: firstRule) {
		if (content.indexOf(rule) != -1) {
			content = content.substring(content.indexOf(rule) + rule.length(), content.length());
			break;
		}
	}
	for (String rule: endRule) {
		if (content.indexOf(rule) != -1) {
			content = content.substring(0, content.indexOf(rule));
			break;
		}
	}
	return content.trim();
}

def boolean checkTime(Date input) {
	boolean result = false;
	if (input.getTime() - dateFormat(jobtxdate).getTime() > 0) {
		result = true;
	}
	return result;
}

def Document getDoc(String url) {
	Document doc = null;
	for (int k = 0; k < keyList.size(); k++) {
		try {
			DocInfo di = surf(url + keyList.get(keyIndex));
			if (di.status == 200) {
				doc = di.htmlSrc;
				return doc;
			} else if (check(di)) {
				changeKey();
				continue;
			}
			return doc;
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	logger.info("All the keys had been exhausted.");
	// System.exit(0);
	return null;

}

def void changeKey() {
	// get user key randomly
	keyIndex++;
	if (keyIndex >= keyList.size()){
		keyIndex = 0;
	}
	logger.info("changeKey - index:" + keyIndex + ", key:" + keyList.get(keyIndex));
	println "now url: "+runUrl.split("&key=")[0];
}

class DocInfo {
	private int status;
	private Document htmlSrc;

	@Override
	public String toString() {
		return "DocInfo [status=" + status + ", htmlSrc=" + htmlSrc + "]";
	}

	private int getStatus() {
		return status;
	}

	private void setStatus(int status) {
		this.status = status;
	}

	private Document getHtmlSrc() {
		return htmlSrc;
	}

	private void setHtmlSrc(Document htmlSrc) {
		this.htmlSrc = htmlSrc;
	}
}

def DocInfo surf(String url) {
	DocInfo di = new DocInfo();
	try {
		URL u = new URL(url);
		runUrl = url;
		int status;
		if ("https".equalsIgnoreCase(u.getProtocol())) {
			ignoreSsl();
		}
		URLConnection conn = getConnection(u);
		status = ((HttpURLConnection) conn).getResponseCode();
		InputStream streamSource = null;
		if (status == 200) {
			streamSource = conn.getInputStream();
		} else {
			for (int i = 0; i < 3; i++) {
				Thread.sleep(2000);
				conn = getConnection(u);
				status = ((HttpURLConnection) conn).getResponseCode();
				if (status == 200) {
					streamSource = conn.getInputStream();
					break;
				} else {
					streamSource = ((HttpURLConnection) conn).getErrorStream();
				}
			}
		}

		BufferedReader reader = new BufferedReader(new InputStreamReader(streamSource, "UTF-8"));
		StringBuilder sb = new StringBuilder();
		String line = null;
		while ((line = reader.readLine()) != null) {
			sb.append(line + "\n");
		}
		reader.close();

		di.setStatus(status);
		di.setHtmlSrc(Jsoup.parse(toUtf8(sb.toString())));

	} catch (Exception e) {
		e.printStackTrace();
	}
	return di;
}

def boolean check(DocInfo input) {
	boolean result = false;
	if(input.status == 403 && (input.htmlSrc.text().contains("\"reason\": \"dailyLimitExceeded\",") ||
	input.htmlSrc.text().contains("\"reason\": \"quotaExceeded\","))) {
		result = true;
	}else if (input.status == 403 && (input.htmlSrc.text().contains("\"reason\": \"accessNotConfigured\","))) {
		result = true

	}else if(input.status == 403 && (input.htmlSrc.text().contains("\"reason\": \"commentsDisabled\","))){
		logger.info("This video has closed the comment.    url: "+runUrl)

	}else {
		logger.warn("[WARNING] - Found new api error condition.")
		logger.warn(input.htmlSrc)
	}


	return result;
}

def URLConnection getConnection(URL u) throws Exception {
	URLConnection conn;
	conn = u.openConnection();
	conn.setRequestProperty("User-Agent", "Mozilla/5.0");
	conn.getHeaderFields();
	conn.setConnectTimeout(10000);
	conn.setReadTimeout(10000);
	return conn;
}

def void ignoreSsl() throws Exception {
	HostnameVerifier hv = new HostnameVerifier() {
				public boolean verify(String urlHostName, SSLSession session) {
					System.out.println("Warning: URL Host: " + urlHostName + " vs. " + session.getPeerHost());
					return true;
				}
			};
	trustAllHttpsCertificates();
	HttpsURLConnection.setDefaultHostnameVerifier(hv);
}

def void trustAllHttpsCertificates() throws Exception {
	TrustManager[] trustAllCerts = new TrustManager[1];
	TrustManager tm = new miTM();
	trustAllCerts[0] = tm;
	SSLContext sc = SSLContext.getInstance("SSL");
	sc.init(null, trustAllCerts, null);
	HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
}

class miTM implements TrustManager, X509TrustManager {
	public X509Certificate[] getAcceptedIssuers() {
		return null;
	}

	public boolean isServerTrusted(X509Certificate[] certs) {
		return true;
	}

	public boolean isClientTrusted(X509Certificate[] certs) {
		return true;
	}

	public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {
		return;
	}

	public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {
		return;
	}
}

def String toUtf8(String str) throws Exception {
	str = str.replaceAll("\\u0000", "");
	return new String(str.getBytes("UTF-8"), "UTF-8");
}

def String MD5(String input) {
	if (input == null)
		return null;
	try {
		MessageDigest md = MessageDigest.getInstance("MD5");
		md.update(input.getBytes());
		String md5 = new BigInteger(1, md.digest()).toString(16);
		return fillMD5(md5);
	} catch (Exception e) {
		throw new RuntimeException("MD5 failed: " + e.getMessage(), e);
	}
}

def String fillMD5(String md5) {
	return md5.length() == 32 ? md5 : fillMD5("0" + md5);
}

def getStackTrace(e) {
	StringWriter sw = new StringWriter();
	e.printStackTrace(new PrintWriter(sw));
	return sw.toString();
}