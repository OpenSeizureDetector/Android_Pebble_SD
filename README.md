OpenSeizureDetector - Android App
=================================

This seizure detector uses a Garmin smart watch.
The watch has an accelerometer, heart rate sensor and a bluetooth radio to talk to another computer.  
See (the OpenSeizureDetector Web Site)[https://www.openseizuredetector.org.uk/] for more details.

Principle of Operation
======================
It is based on an accelerometer monitoring movement.  It uses a fourier
transform to extract the frequency spectrum of the movement, and monitors
movements in a given frequency band.   The idea is that it will detect the
rhythmic movements associated with a seizure, but not normal day to day
activities.

If the acceleration within the given frequency band is more than a
threshod value, it starts a timer.  If the acceleration remains above
the threshold for a given period, it issues a warning beep.
If it remains above the threshold for a longer specified period, the unit
alarms (continuous tone rather than beep).


Licence
=======
My code is licenced under the GNU Public Licence - for associated libraries 
please see Credits below.

Credits
=======
The following libraries are used:
* (SYLT-FFT)[https://github.com/stg/SYLT-FFT] by D. Taylor.
* (NanoHTTPD)[https://github.com/NanoHttpd/nanohttpd]
* (jQuery)[http://jquery.org]
* (jBeep)[http://www.ultraduz.com.br]
* (Chartjs)[http://www.chartjs.org]
* (MPAndroidChart)[https://github.com/PhilJay/MPAndroidChart]

Logo based on ["Star of life2" by Verdy p - Own work. Licensed under Public Domain via Wikimedia Commons](http://commons.wikimedia.org/wiki/File:Star_of_life2.svg#mediaviewer/File:Star_of_life2.svg).

Alarm Bell Icon by <a href="https://icon54.com/" title="Pixel perfect">Pixel perfect</a> from <a href="https://www.flaticon.com/" title="Flaticon"> www.flaticon.com</a>

Other icons crated using http://romannurik.github.io/AndroidAssetStudio.

Audio Alarm sounds from freesound https://freesound.org/people/coltonmanz/sounds/381382/, https://freesound.org/people/NoiseCollector/sounds/4270/, https://freesound.org/people/pistak23/sounds/271632/



Graham Jones, 03 December 2017.  (graham@openseizuredetector.org.uk)
