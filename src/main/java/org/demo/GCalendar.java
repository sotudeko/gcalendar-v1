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
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
	private static final String FILTER_FILE_PATH = "./filter.txt";
	//private static final String CUSTOMER_NAMES_FILE_PATH = "./customer-names.txt";

	// private static List<String> fullCustomerNames;
	// private static boolean customerNamesAvailable = false;
	//static HashMap<String, String> customerNamesMap = new HashMap<String, String>();
	private static Map<String, String> customerMeetings = new TreeMap<>();

	private static List<String> excludeEventFilter;
	private static boolean filterAvailable = false;

	public static void main(final String... args) throws Exception {

		if (args.length == 0) {
			errorExit(
					"start and end dates required in format yyyy-mm-dd. e.g. java -jar gcalendar.java 2020-07-13 2020-07-17");
		}

		final String startDate = args[0];
		final String endDate = args[1];

		final DateTime timeMin = parseDate("startDate", startDate);
		final DateTime timeMax = parseDate("endDate", endDate);

		final String outputFile = startDate + "_" + endDate + ".csv";

		final File f = new File(outputFile);
		BufferedWriter bw = null;

		if (f.exists()) {
			f.delete();
		}

		if (f.createNewFile()) {
			final FileOutputStream fos = new FileOutputStream(f);
			bw = new BufferedWriter(new OutputStreamWriter(fos));
			//bw.write("Id,Event,Date,Time\n");
		}

		final List<String> calendarIds = readFile(CALENDARIDS_FILE_PATH);

		if (calendarIds.size() == 0) {
			errorExit("calendar ids file not found: " + CALENDARIDS_FILE_PATH);
		}

		// file with list of keywords for excluding events
		excludeEventFilter = readFile(FILTER_FILE_PATH);

		if (excludeEventFilter.size() > 0) {
			filterAvailable = true;
		}

		int count = 0;

		// list items for each calendar
		for (final String calendarId : calendarIds) {

			if (calendarId.startsWith("#")) {
				continue;
			}

			final List<Event> calendarItems = getCalendarEvents(calendarId, timeMin, timeMax);

			if (calendarItems.isEmpty()) {
				System.out.println(calendarId + ": No upcoming events found.");
				continue;
			} 

			final List<String> ptoDays = getPTODays(calendarItems);

			final int i = listCalendarEvents(calendarId, calendarItems, ptoDays, timeMin, timeMax, bw);
			count += i;
		}

		for (Map.Entry<String, String> entry : customerMeetings.entrySet()){
			String opStr = entry.getKey();
			bw.write((opStr));
			bw.newLine();
		}
		
		if (count > 0) {
			//bw.write("Total entries: " + count);
			System.out.println("\nOutputfile: " + outputFile);
		}

		bw.close();
		System.out.println("Total entries: " + count);
	}


	private static List<Event> getCalendarEvents(final String calendarId, final DateTime timeMin, final DateTime timeMax)
			throws GeneralSecurityException, IOException {

		List<Event> items = new ArrayList<Event>();

		final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();

		final Calendar service = new Calendar.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
				.setApplicationName(APPLICATION_NAME).build();

		try {
			final Events events = service
							.events()
							.list(calendarId)
							.setTimeMin(timeMin)
							.setTimeMax(timeMax)
							.setOrderBy("startTime")
							.setSingleEvents(true)
							.execute();

		  items = events.getItems();
		} 
		catch (Exception e) {
			//TODO: handle exception
		}
		
		return items;
	 }


	private static void errorExit(final String msg){
		System.err.println("error: " + msg);
		System.exit(-1);
	}


	public static int listCalendarEvents(
			final String calendarId, 
			final List<Event> items, 
			final List<String> ptoDays, 
			final DateTime timeMin,
			final DateTime timeMax,
			final BufferedWriter bw) throws IOException, ParseException {

		int i = 0;

		for (final Event event : items) {

			if (event.getSummary() == null){
				continue;
			}

			final String eventSummary = event.getSummary();

			if (filterAvailable && excludeEventByFilter(eventSummary.toLowerCase())) {
				continue;
			}

			if (excludeEvent(eventSummary.toLowerCase())) {
				continue;
			}

			final List<EventAttendee> attendees = event.getAttendees();

			if (attendees == null || isDeclinedByCalendarId(calendarId, attendees)) {
				continue;
			}

			final String eday = getDayStr(event.getStart());

			if (isCustomerMeeting(attendees) && isNotPTODay(ptoDays, eday)) {
				getCustomerNameForMeeting(calendarId, event);
				i++;
			}
		}

		final String outputSummary = calendarId + "," + i + "\n";
		System.out.print(outputSummary);

		return i;
	}


	private static List<String> getPTODays(final List<Event> items) throws ParseException {
		final List<String> ptoDays = new ArrayList<String>();
		final long oneDay = 86400000;

		for (final Event event : items) {

			if (event.getSummary() == null) {
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
					nd += oneDay;
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

		if (eday.length() > 10) {
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

		if (etime.length() > 10) {
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


	private static String getDateStr(final long ms) {
		final DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
		final String data = df.format(new Date(ms));
		return data;
	}


	private static boolean isNotPTODay(final List<String> ptoDays, final String edate) {
		boolean status = true;

		for (final String ptoDay : ptoDays) {
			if (edate.equals(ptoDay)) {
				status = false;
				continue;
			}
		}

		return status;
	}


	private static boolean isDeclinedByCalendarId(final String calendarId, final List<EventAttendee> attendees) {
		boolean status = false;

		for (final EventAttendee attendee : attendees) {

			if (attendee.getEmail().equalsIgnoreCase(calendarId)
					&& attendee.getResponseStatus().equalsIgnoreCase("declined")) {
				status = true;
				continue;
			}
		}

		return status;
	}


	private static boolean excludeEventByFilter(final String eventSummary) throws IOException {

     	boolean status = false;

     	for (final String event : excludeEventFilter) {

     		final String eventStr = event.toLowerCase();

     		if (!isBlankString(eventStr) && eventSummary.contains(eventStr)) {
     			status = true;
     		}
     	}

     	return status;

	}

	private static boolean isBlankString(final String string) {
		return string == null || string.trim().isEmpty();
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

	private static void getCustomerNameForMeeting(String calendarId, Event event) {
		Map<String, Integer> cnames = new TreeMap<>();

		final List<EventAttendee> attendees = event.getAttendees();
		final String eday = getDayStr(event.getStart());
		final String etime = getTimeStr(event);
		final String eventSummary = event.getSummary();
		eventSummary.replaceAll(",", ";");

		String meetingDesc = calendarId + "," + eventSummary + "," + eday + "," + etime;
		System.out.println(meetingDesc);

		String customerName = "";

		for (final EventAttendee attendee : attendees) {
			if (attendee != null) {
				final String email = attendee.getEmail();

				if (!email.contains("@sonatype.com")) {
					final String[] parts = email.split("@");
					String address = parts[1];

					address = address.substring(0, address.lastIndexOf("."));
					address = cleanupAddress(address);

					if (!excludeAddress(address)){
						if (!cnames.containsKey(address)){
							customerName = address + "/" + customerName;
						}
						cnames.put(address, 1);
					}
				}
			}
		}

		if (customerName.length() > 0){
			customerMeetings.put(customerName.substring(0, customerName.length() - 1), meetingDesc);
		}

		//return (customerName == null || customerName.length() == 0) ? null : (customerName.substring(0, customerName.length() - 1));
		return;
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

	private static boolean excludeEvent(String eventSummary) {

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
				eventSummary.contains("brightspot") ||
				eventSummary.contains("out of office")) {
			return true;
		} 
		else {
			return false;
		}

		}
	
		private static boolean excludeAddress(String address){
			if (address.contains("external.") ||
					address.equals("gmail") ||
					address.contains("ap.") ||
					address.contains("engineering") ||
					address.equals("ext") ||
					address.equals("db") ||
					address.contains("asia") ||
			    address.contains("externe.")) {
				return true;
			}
			else {
				return false;
			}
		}

		private static String cleanupAddress(String address){
			address = address.replaceFirst("(^uk\\.)", "");
			address = address.replaceFirst("(^ext\\.)", "");
			address = address.replaceFirst("(\\.co$)", "");
			address = address.replaceFirst("(\\.gov$)", "");

			return address;
		}
}


