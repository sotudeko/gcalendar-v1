package org.demo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
//import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

public class GCalendar {

	private static final String APPLICATION_NAME = "Google Calendar Information";
	private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	private static final String TOKENS_DIRECTORY_PATH = "tokens";

	/**
	 * Global instance of the scopes required by this quickstart. If modifying these
	 * scopes, delete your previously saved tokens/ folder.
	 */
	private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR_READONLY);
	private static final String CREDENTIALS_FILE_PATH = "./credentials.json";
	private static final String CALENDARIDS_FILE_PATH = "./calendar-ids.txt";

	public static void main(final String... args) throws Exception {

		if (args.length == 0) {
			errorExit("start and end dates required in format yyyy-mm-dd. e.g. java -jar gcalendar.java 2020-07-13 2020-07-17");
		}

		String startDate = args[0];
		String endDate = args[1];

		DateTime timeMin = parseDate("startDate", startDate);
		DateTime timeMax = parseDate("endDate", endDate);

		String outputFile = startDate + "_" + endDate + ".csv";

		File f = new File(outputFile);
		BufferedWriter bw = null;

		if (f.exists()) {
			f.delete();
		}

		if (f.createNewFile()) {
			final FileOutputStream fos = new FileOutputStream(f);
			bw = new BufferedWriter(new OutputStreamWriter(fos));
			bw.write("Id,Event,Date,Time\n");
		}

		// Build a new authorized API client service.
		// final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

		// final Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
		// 		.setApplicationName(APPLICATION_NAME).build();

		List<String> calendarIds = readFile(CALENDARIDS_FILE_PATH);

		if (calendarIds.size() == 0) {
			errorExit("calendar ids file not found: " + CALENDARIDS_FILE_PATH);
		}

		int count = 0;

		for (String calendarId : calendarIds) {

			if (calendarId.startsWith("#")) {
				continue;
			}

			List<Event> calendarItems = getCalendarEvents(calendarId, timeMin, timeMax);

			if (calendarItems.isEmpty()) {
				System.out.println("No upcoming events found.");
				continue;
			} 

			List<String> ptoDays = getPTODays(calendarItems);

			int i = listCalendarEvents(calendarId, calendarItems, ptoDays, timeMin, timeMax, bw);
			count += i;

		}

		if (count > 0) {
			bw.write("Total entries: " + count);
			System.out.println("\nOutputfile: " + outputFile);
		}

		bw.close();
		System.out.println("Total entries: " + count);
	}

	private static List<Event> getCalendarEvents(String calendarId, DateTime timeMin, DateTime timeMax)
			throws GeneralSecurityException, IOException {

		final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

		final Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
				.setApplicationName(APPLICATION_NAME).build();

		final Events events = service
							.events()
							.list(calendarId)
							.setTimeMin(timeMin)
							.setTimeMax(timeMax)
							.setOrderBy("startTime")
							.setSingleEvents(true)
							.execute();

		final List<Event> items = events.getItems();

		return items;
	 }

	private static void errorExit(final String msg){
		System.err.println("error: " + msg);
		System.exit(-1);
	}

	public static int listCalendarEvents(
			String calendarId, 
			List<Event> items, 
			List<String> ptoDays, 
			DateTime timeMin,
			DateTime timeMax,
			BufferedWriter bw) throws IOException, ParseException {

		int i = 0;

		for (final Event event : items) {

			if (event.getSummary() == null){
				continue;
			}

			String eventSummary = event.getSummary();

			if (excludeEvent(eventSummary.toLowerCase())) {
				continue;
			}

			List<EventAttendee> attendees = event.getAttendees();

			if (attendees == null || isDeclined(calendarId, attendees)) {
				continue;
			}

			final String eday = getDayStr(event.getStart());
			final String etime = getTimeStr(event);

			if (isCustomerMeeting(attendees) && isNotPTODay(ptoDays, eday)) {
				String str = calendarId + "," + event.getSummary() + "," + eday + "," + etime;

				bw.write(str);
				bw.newLine();
				i++;
			}
		}

		String outputSummary = calendarId + "," + i + "\n";
		System.out.print(outputSummary);
		
		return i;
	}

	private static List<String> getPTODays(final List<Event> items) throws ParseException {
		final List<String> ptoDays = new ArrayList<String>();
		final long oneDay = 86400000;

		for (Event event : items) {

			if (event.getSummary() == null){
				continue;
			}

			final String eventSummary = event.getSummary().toLowerCase();

			if (eventSummary.contains("pto") || eventSummary.contains("out of office")) {
				final String startDayStr = getDayStr(event.getStart());
				final String endDayStr = getDayStr(event.getEnd());

				long nd = getDateMs(startDayStr);
				final long ed = getDateMs(endDayStr);

				String nextDateStr = startDayStr;

				do {
					ptoDays.add(nextDateStr);
					nd+= oneDay;
					nextDateStr = getDateStr(nd);
				} while (nd <= ed);
			}
		}

		return ptoDays;
	}

	private static String getDayStr(final EventDateTime eventDateTime) {
		DateTime start = eventDateTime.getDateTime();

		if (start == null) {
			start = eventDateTime.getDate();
		}

		String eday = start.toString();

		if (eday.length() > 10){
			eday = eday.substring(0, eday.indexOf("T"));
		}

		return eday;
	}

	private static String getTimeStr(final Event e) {
		DateTime start = e.getStart().getDateTime();

		if (start == null) {
			start = e.getStart().getDate();
		}

		String etime = start.toString();

		if (etime.length() > 10){
			etime = etime.substring(etime.indexOf("T") + 1, etime.indexOf("."));
		}

		return etime;
	}

	private static Long getDateMs(final String str) throws ParseException {
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
		final Date date = sdf.parse(str);
		final long millis = date.getTime();
		return millis;
	}

	private static String getDateStr(final long ms){
		final DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		final String data = df.format(new Date(ms));
		return data;
	}

	private static boolean isNotPTODay(List<String> ptoDays, String edate) {
		boolean status = true;

		for (final String ptoDay : ptoDays) {
			if (edate.equals(ptoDay)){
				status = false;
				continue;
			}
		}	
					
		return status;
	}

	private static boolean isDeclined(final String calendarId, final List<EventAttendee> attendees) {
		boolean status = false;

		for (final EventAttendee attendee : attendees) {

			if (attendee.getEmail().equalsIgnoreCase(calendarId) && attendee.getResponseStatus().equalsIgnoreCase("declined")){
				status = true;
				continue;
			}
		}

		return status;
	}

	private static boolean excludeEvent(final String eventSummary) {

		if (eventSummary.contains("scrum") || 
		    eventSummary.contains("ipod bi-weekly") || 
			eventSummary.contains("biweekly") || 
			eventSummary.contains("battle buddies") ||
			eventSummary.contains("pm/em/se") || 
			eventSummary.contains("talkto") ||
			eventSummary.contains("redlight") || 
			eventSummary.contains("international team") ||
			eventSummary.contains("1:1") || 
			eventSummary.contains("lunch") || 
			eventSummary.contains("pto") ||
			eventSummary.contains("emea weekly") || 
			eventSummary.contains("tech enablement") ||
			eventSummary.contains("open space") || 
			eventSummary.contains("brightspots") ||
			eventSummary.contains("out of office")) {
			return true;
		} 
		else {
			return false;
		}
	}

	private static boolean isCustomerMeeting(final List<EventAttendee> attendees) {

		boolean status = false;

		for (final EventAttendee attendee : attendees) {

			if (attendee != null) {
				final String email = attendee.getEmail();

				if (!email.contains("@sonatype.com")) {
					status = true;
					continue;
				}
			}
		}

		return status;
	}

	private static DateTime parseDate(final String period, final String periodStr) {

		String periodTime;

		if (period == "startDate")
			periodTime = "01:00:00";
		else
			periodTime = "23:00:00";

		final String[] periodDate = periodStr.split("-");

		final DateTime periodDateDt = new DateTime(getDateMs(periodDate[0], periodDate[1], periodDate[2], periodTime));

		return periodDateDt;
	}

	/**
	 * Creates an authorized Credential object.
	 * 
	 * @param HTTP_TRANSPORT The network HTTP Transport.
	 * @return An authorized Credential object.
	 * @throws IOException If the credentials.json file cannot be found.
	 */
	private static Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT) throws IOException {

		// Load client secrets.
		final InputStream in = new FileInputStream(CREDENTIALS_FILE_PATH);

		if (in == null) {
            throw new FileNotFoundException("Resource not found: " + CREDENTIALS_FILE_PATH);
		}
		
		final GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(JSON_FACTORY, new InputStreamReader(in));

		// Build flow and trigger user authorization request.
		final GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow
						.Builder(HTTP_TRANSPORT, JSON_FACTORY,clientSecrets, SCOPES)
						.setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
						.setAccessType("offline")
						.build();

		final LocalServerReceiver receiver = new LocalServerReceiver
											.Builder()
											.setPort(8888)
											.build();

		return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
	}

	public static long getDateMs(final String yr, final String mth, final String day, final String time) {

		final String myDate = yr + "/" + mth + "/" + day + " " + time;

		final LocalDateTime localDateTime = LocalDateTime.parse(myDate, DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"));

		final long millis = localDateTime
						.atZone(ZoneId
						.systemDefault())
						.toInstant()
						.toEpochMilli();

		return millis;
	}

	public static List<String> readFile(final String inputFile) throws IOException {

		final List<String> lines = new ArrayList<String>();

		final File f = new File(inputFile);

		if (f.exists() && !f.isDirectory() && f.length() > 0) {

			final BufferedReader br = new BufferedReader(new FileReader(f));
			
			String line;
			
			while((line = br.readLine()) != null) {
				lines.add(line);
			}

			br.close();
		}
	
        return lines;
	}
}


