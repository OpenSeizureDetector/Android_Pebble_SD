OpenSeizureDetector Android App - RELEASE NOTES
==============================================

Version 4.0.0
  - Logs all seizure detector data to local database
  - Adds 'Data Sharing' functionality to upload data to remote database and edit events to say if they are false alarms or genuine seizures
  - Settings screens tidied up (removed some unnecessary options to simplify settings)
  - Added check of whether the App is being 'Optimised' for battery usage by the Android System
  - Fixed problem where the web server receiving data will send it to the analysis routines, even if the data source is not set to Garmin.
    - for example if you set the data source to phone, but ran OSD on a garmin watch, the data would oscillate between phone and watch
    data
  -       

