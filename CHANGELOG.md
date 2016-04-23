	OpenSeizureDetector Android App - Change Log
	============================================

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


	
