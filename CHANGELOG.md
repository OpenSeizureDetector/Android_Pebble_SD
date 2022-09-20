	OpenSeizureDetector Android App - Change Log
	============================================
    V4.1.0 - Added experimental support for neural network based seizure detector.
    V4.0.7 - Improvements to Data Sharing data log manager screen
        - Removed automatic refresh of shared data events list (Issue #62)
        - Added option to include warnings in shared data events list (Issue #64)
        - Added catch of NullPointerException in LogManager in case of network disruption (Issue #61)
        - Added 'demo mode' on main screen when phone data source selected and highighted it in warning colour (Issue #56)
        - Changed default local data retention time to 7 days (from 28) - works around #63
    V4.0.6 - fixed issue with O2sat data not being recorded to database
    V4.0.5 - Added support for 3D data logging
            - Fixed issue with seizure reporting crashing if quotation marks included in text.
    V4.0.4 - Added support for Data Sharing system
    V3.6.2 - Nov 2020
        - Extended Polish translation further
        - Improved system log output to help with answering queries from users.
    V3.6.1 - Nov 2020
        - Extended polish translation to all settings screens.
        - Possible fix for issue with failing to shutdown system sometimes.
	V3.6.0 - Sep 2020
	    - Added a phone sensor datasource, primarily for people to use for testing without needing a watch.
	V3.5.0 - Aug 2020
	     - Added broadcast to request phone call dial alert (handled by separate app OpenSeizureDetector Dialler).
	     - Added UUID string to SMS alerts so they can be detected by a custom SMS receiver on the carer's phone.
	V3.4.0 - Aug2020
	    - Added support for BLE data source
	V3.2.1 - Aug2020
		- Addition of Spanish Translation, and correction of crash report wording in German.
	V3.2.0 - mar2020
        - Added support for additional languages - Sweedish, German and Russian included in this release
        - Added support for additional GarminSD settings data fields to record the watch app version number etc and
          log that info to the SysLog file.
        - Made only a subset of settings available by default, unless the user enables 'advanced mode' to make them all visible.
    V3.1.15 - 01mar2020
        - Added German Language support for main and startup app screens.
    V3.1.14 - 27feb2020
        - Fixed the web interface, which stopped working in V3.1.13
        - Separated the user interface text into separate strings.xml file so we can
          have multi-lingual versions of the app (if people offer to do the translations
          for me, because I can't do it myself!)
        - Note that I think Google has forced me to drop support for Android 4.x in a recent
          update - I could only get it to compoile if I increased the minimum android version
          to Android 5.0.   This is a shame because I am one of the 3 Android 4 users....

	V3.1.13 - 16feb2020
	    - Fixed problem with NetworkDataSource not responding to FAULT conditions properly.

    V3.1.12 - 15feb2020
        - Updated web server to return correct application/json mime type to get rid of -400
          errors on garmin watch.   Made error messages json strings too.
          Added hrAlarmStanding value to /data json string, which should get remove
          heart rate alarms working with network datasource.
>>>>>>> master

    V3.1.11 - 23oct2019
        - Updated network data source so it displays heart rate data if it is available.

    V3.1.10 - 20oct2019
        - Added option to make a 'null' heart rate reading an alarm rather than fault condition.

    V3.1.9 - 14jun2019
        - Now requests READ_PHONE_STATE along with SMS permissions (required for some phones to send SMS messages)
        - Fixed issue with Garmin Seizure Detector not producing warnings.
        - Added faut pips for missing heart rate data if heart rate alarm active

    V3.1.8 - 06jun2019
        - Added READ_PHONE_STATE permission which seems to be needed for some phones to send SMS (but not many).

    V3.1.7 - 28apr2919
        - Added Mute function from watch Menu button.

    V3.1.6 - 27apr2919
        - Made the Cancel Audible button inhibit SMS alarms as well as audible beeps.

    V3.1.5 - 27apr2919
        - Fixed crash of MainActivity when server shutdown.

    V3.1.4 - 25apr2019
        - Added a periodic 'Beep' during the SMS timer to alert the user that an SMS is about to be sent.

    V3.1.3 - 22apr2019
        - Moved the SmsTimer creation to the UI thread to avoid a ...looper.prepare() error.

	V3.1.2 - 22apr2019
		- Made fall alarms latch irrespective of the 'latch alarms' setting
		because they are transitory alarms they have to latch to be useful.

	V3.1.1 - 22apr2019
        - Added heart rate to data log.
		- Added Timer to give user opportunity to cancel SMS alarm before it is sent.

    V3.1.0 - 07apr2019
        - Added fall detection algorithm to Garmin/Fitbit data source.

    V3.0.4 - Fixed issues with install watch app, and stopping server resulting in crashes

    V3.0.3- Fixed problem with crash on phone boot if Auto Start on Boot option selected

    V3.0.2 - 27feb2019
        - Corrected issue with app not re-starting properly when run-time permissions changed
        (e.g. send_sms permission)
        - Fixed crash when using MP3 alarms (issue with new android notification system)
	V3.0.1 - 21feb2019
		- Simplified data log output to CSV format for easier processing, and had it log every update rather than one point per minute.

	V3.0.0 - 15feb2019
        - Updated for Android V9
        - Added explicity statement of use of SMS permission in notification
        - Updated to use dynamic permissions
        - Added support for Garmin Data Source, with Heart Rate alarm (and other network based data sources)

    V2.6.0 - 26dec2018
        - Changed SMS code to comply with new Google Play Store requirements.
	    - Added Support for Wifi data source (initially to be used for Garmin_SD)

	V2.5.5 - 23feb2018 - Improved logging to help diagnose network problems
	that result in 'net fault' warnings from network data source
	('wifi-storms').

	V2.5.4 - 03dec2017 - Added option to use either tone generator or MP3 alarm sound, as a user reported problem with tone generator on high end samsung phone.

	V2.5.3 - 10sep2017 - Added Pebble App V2.6 which provides better alarm reliability 
	- no changes to Android App other than first run dialog.

	V2.5.2 - 09 May 2017
	Added support for Pebble App V2.5 which includes a multi-ROI mode to improve sensitivity.

    V2.5.1 - 07 May 2017
	- Improved alarm annunciation for short duration seizures - setting
	Latch Alarms will result in alarms sounding for at least Latch Alarm
	Timer Duration seconds before resetting.

    V2.3.2 - 26 Dec 2016
    - Added first run dialog with message to StartUpActivity.

    V2.3.1 - 19 Dec 2016
    - Changed auto-start feature to start the SDServer background service rather than the StartUpActvity so it will work
    when the phone screen is locked.

    V2.3.0 - 14 Dec 2016
	- Added auto start on phone boot feature (selectable from general settings)
	- Added Location to SMS alarm notifications

    V2.0.8 - 24 Aug 2016
    - Added checks for correct version of pebble watch app for compatibility with this
    Android App.

	V2.0.7 - 18 Aug 2016
	- Added AnalysisPeriod setting to Pebble Datasource to change
	the period between data analyses (rather than the default 5 seconds
	in previous versions).

	V2.0.6 - 25 July 2016
	- Added main activity menu option to view log files (via web browser).
	- Added options to switch off spectrum display on watch to save battery.
	- Changed main screen graph to bar chart and highlights frequency
	region of interest.
	- Fixed problem with log files not showing on web interface.
	- Added system log file to help with de-bugging start-up/shutdown issues.
	- Improved handling of watch app settings to make sure
	they are loaded correctly without having to re-start app (but I'd still recommend re-starting the watch app manually to be sure :) )
	- Reduced ammount of bluetooth comms to the watch to save battery.
	- Added support for future watch app features (such as raw mode and digital
	filter mode).
	- Added watch app to Android phone app package so watch app can be
	installed directly from phone rather than using pebble store - to make sure that watch app and Android app are always compatible.

	V2.0.3 - 23 April 2016
		Further modification to beep code to avoid occasional crashes
		if system tries to beep during a re-start.
	        Log faults to alarm log on SD Card.
	V2.0.2 - 13 April 2016
		Modified 'beep' code to try to avoid crashes on some systems.

	V2.0.1 - 02 April 2016
		Fixed issue with fault alarms not sounding if watch disconnects from phone.
	
	V2.0 - 30 March 2016
	Behind the Scenes
	- Merged the server and client apps so only one app is needed to remove code duplication.
	- Major rewrite of background service back-end to handle different data sources - either pebble or network so it can act as either server or client.

	User Interface
	- Added start-up screen that shows the bits of the system starting and confirms they work before showing the main screen.
	- start-up screen has buttons to change the settings.
	- If pebble datasource is selected, start-up screen has an option to
	open the pebble app to manage pebble connection issues, and to install the watch-app directly.
	- The main screen has been tidied up a bit, and now includes bar graphs to show how close to alarming the current movement is..
	- Settings screen broken down into different logical screens to make it easier to find a setting to change.
	- Changing a setting now re-starts the whole app to make sure the new setting is being used.
	- Added an 'about' page with links to http://openseizuredetector.org.uk web site and copyright and acknowledgement notices.
	- Added ability to latch alarms so they have to be actively accepted to silence the alarm, rather than it re-setting when the movement stops.
	- Fixed problem with the system being difficult to shut down if as multiple instances of main screen could be active at once.


	
