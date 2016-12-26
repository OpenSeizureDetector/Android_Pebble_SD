	OpenSeizureDetector Android App - Change Log
	============================================

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


	
