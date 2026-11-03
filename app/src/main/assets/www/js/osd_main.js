// Request settings from the server.
function get_settings() {
   $.ajax({url:"/settings",success:populate_settings});
};


function populate_settings(dataObj) {
   sd_settings = dataObj;    // holds adaptiveHrAlarmActive / averageHrAlarmActive, which /data does not
   setSignal(dataObj['watchSignalStrength']);
   if (!battery_from_data) setBattery(dataObj['batteryPc']);
}

// ---- settings you may want to tune ----
var BEEP_REPEAT_MS = 10000;      // browser beep repeats at most this often during a warning/alarm (0 = never beep)
var SETTINGS_POLL_MS = 15000;    // how often /settings is read (signal strength, enabled detectors)
var SIGNAL_WARN_DBM = -80;       // watch signal tile turns orange below this ...
var SIGNAL_ALARM_DBM = -90;      // ... and red below this

var STALE_AFTER_MS = 15000;      // no NEW reading for this long => red "No new data" (readings arrive every 5 s)
var FAIL_LIMIT = 2;              // this many failed requests in a row => red "No connection to the phone"

var sd_settings = {};
var settings_needed = true;      // becomes false once /data itself carries the enabled flags and signal strength
var fail_count = 0;
var last_stamp = "";             // dataTimeStr of the newest reading seen
var last_new_ms = 0;             // browser time at which that reading first arrived
var last_time_str = "";          // its time of day, for the message
var last_beep = 0;

// Status card text: phrase on the first line, time of the reading on the second.
function statusHtml(line1, line2) {
   return '<div class="stxt"><div>' + line1 + '</div>' +
          (line2 ? '<div class="stime">' + line2 + '</div>' : '') + '</div>';
}

// ---- browser mute: silences the beep in THIS browser only (the phone's own alarm is not touched) ----
var MUTE_MS = 600000;            // 10 minutes
var mute_until = 0;              // browser time (ms) when the mute ends; 0 = not muted

function isMuted() { return Date.now() < mute_until; }

function updateMuteButton() {
   var left = Math.ceil((mute_until - Date.now()) / 1000);
   if (left > 0) {
       $("#muteButton").addClass("muted").html("Alarm sound muted: " + left + " s<small>tap to unmute</small>");
   } else {
       mute_until = 0;
       $("#muteButton").removeClass("muted").html("Mute alarm sound<small>for 10 minutes</small>");
   }
}

function toggleBrowserMute() {
   mute_until = isMuted() ? 0 : Date.now() + MUTE_MS;
   updateMuteButton();
}

function beepMaybe() {
   var now = Date.now();
   if (BEEP_REPEAT_MS > 0 && !isMuted() && now - last_beep >= BEEP_REPEAT_MS) {
       last_beep = now;
       jBeep('js/jBeep.wav');
   }
}

function levelColour(v, warnBelow, alarmBelow) {
   return v < alarmBelow ? "red" : (v < warnBelow ? "orange" : "blue");
}
function validNum(v) { return !(typeof v === 'undefined' || v === null || isNaN(v)); }

function setSignal(dbm) {
   if (!validNum(dbm)) return;
   if (dbm >= -1) {      // SdData leaves 0 (or -1) when the data source has no signal strength
       $("#pebStat4").html("Watch signal = n/a").css("background-color", "");
       return;
   }
   $("#pebStat4").html("Watch signal = " + dbm + " dBm")
                 .css("background-color", levelColour(dbm, SIGNAL_WARN_DBM, SIGNAL_ALARM_DBM));
}

function setPhoneBattery(pc) {
   if (!validNum(pc)) return;
   $("#pebStat5").html("Phone battery = " + pc + " %")
                 .css("background-color", levelColour(pc, 30, 20));
}

function parseStamp(st) {        // "20261030T125805" -> ms, or NaN
   var m = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})$/.exec(String(st));
   return m ? new Date(m[1], m[2] - 1, m[3], m[4], m[5], m[6]).getTime() : NaN;
}

function showProblem(text) {
   $("#benStat").html(statusHtml(text, last_time_str ? "last data " + last_time_str : ""))
                .css("background-color", "red");
   beepMaybe();
}

// ---- alarm type icons ----
function on(v) { return v === true; }
var HEART = '<path d="M12 20s-7-4.5-7-10a4 4 0 0 1 7-2.5A4 4 0 0 1 19 10c0 5.5-7 10-7 10z"/>';
var ALARM_TYPES = [
  {id:"osd",  label:"Motion", icon:'<path d="M2 12h4l3-7 4 14 3-7h6"/>',
     active:function(d,s){return on(d.OsdAlarmActive)||on(d.osdAlarmActive)||on(s.osdAlarmActive);},
     level:function(d){return d.osdAlgState;}},
  {id:"flap", label:"Flap", icon:'<path d="M3 8c3-3 6 3 9 0s6 3 9 0M3 16c3-3 6 3 9 0s6 3 9 0"/>',
     active:function(d,s){return on(d.FlapAlarmActive);},
     level:function(d){return d.flapAlgState;}},
  {id:"cnn",  label:"AI", icon:'<path d="M7 7h10v10H7zM10 3v4M14 3v4M10 17v4M14 17v4M3 10h4M3 14h4M17 10h4M17 14h4"/>',
     active:function(d,s){return on(d.cnnAlarmActive)||on(d.CnnAlarmActive)||on(s.cnnAlarmActive);},
     level:function(d){return d.cnnAlgState;}},
  {id:"hr",   label:"Heart rate", icon:HEART,
     active:function(d,s){return on(d.hrAlarmActive);},
     level:function(d){return Math.max(d.hrAlgState||0, d.hrAlarmStanding?2:0);}},
  {id:"ahr",  label:"Adaptive HR", icon:HEART,
     active:function(d,s){return on(d.adaptiveHrAlarmActive)||on(s.adaptiveHrAlarmActive);},
     level:function(d){return d.adaptiveHrAlarmStanding?2:0;}},
  {id:"avhr", label:"Average HR", icon:HEART,
     active:function(d,s){return on(d.averageHrAlarmActive)||on(s.averageHrAlarmActive);},
     level:function(d){return d.averageHrAlarmStanding?2:0;}},
  {id:"o2",   label:"O2 sat", icon:'<circle cx="12" cy="12" r="9"/><text x="12" y="15.5" text-anchor="middle" font-size="9">O2</text>',
     active:function(d,s){return on(d.o2SatAlarmActive);},
     level:function(d){return d.o2SatAlarmStanding?2:0;}},
  {id:"fall", label:"Fall", icon:'<path d="M12 4v13M6 11l6 6 6-6M5 21h14"/>',
     active:function(d,s){return false;},          // no "active" flag is sent: shown only once it triggers
     level:function(d){return d.fallAlgState;}}
];

function buildAlarmTypes() {
   ALARM_TYPES.forEach(function(t) {
      $("#alarmTypes").append('<div class="atype lv0" id="at_' + t.id + '" style="display:none">' +
         '<svg viewBox="0 0 24 24" aria-hidden="true">' + t.icon + '</svg><span>' + t.label + '</span></div>');
   });
}

function updateAlarmTypes(d) {
   ALARM_TYPES.forEach(function(t) {
      var lvl = Math.min(2, Math.max(0, Number(t.level(d)) || 0));
      var el = $("#at_" + t.id);
      if (t.active(d, sd_settings) || lvl > 0) {          // a triggered detector is never hidden
         el.attr("class", "atype lv" + lvl)
           .attr("title", t.label + (lvl == 2 ? ": ALARM" : lvl == 1 ? ": warning" : ": enabled"))
           .show();
      } else {
         el.hide();
      }
   });
}

// Watch battery tile.  batteryPc comes either from /data (preferred, if the server
// includes it) or from /settings (fallback, polled slowly because it changes slowly).
var battery_from_data = false;

function setBattery(batteryPc) {
   if (typeof batteryPc === 'undefined' || batteryPc === null || isNaN(batteryPc)) return;
   var bgCol = "blue";
   if (batteryPc < 30) bgCol = "orange";
   if (batteryPc < 20) bgCol = "red";
   $("#pebStat3").html("Watch battery = " + batteryPc + " %")
                 .css("background-color", bgCol);
}

// Request data from the server.
function get_data() {
   $.ajax({url:"/data", cache:false, timeout:5000,
           success:function(d) { fail_count = 0; process_data(d); },
           error:function() {
               fail_count++;
               if (fail_count >= FAIL_LIMIT) showProblem("No connection to the phone");
           }});
};

function process_data(dataObj) {
    //console.log("process_data");
    //console.log(dataObj);
   var timeStr = dataObj['dataTime'];
   var tm = /\d{1,2}:\d{2}:\d{2}/.exec(String(timeStr));   // show the time only, not the date
   if (tm) timeStr = tm[0];
   var stamp = dataObj['dataTimeStr'];
   var noData = (typeof stamp === 'undefined') || stamp === "00000000T000000" || dataObj['haveData'] === false ||
                /^00-00-00/.test(String(dataObj['dataTime']));
   if (!noData && stamp !== last_stamp) {
       if (last_stamp === "") {      // first reading since this page was opened: it may already be old
           var age0 = Date.now() - parseStamp(stamp);
           last_new_ms = (age0 > 60000 && age0 < 86400000) ? Date.now() - age0 : Date.now();
       } else {
           last_new_ms = Date.now();
       }
       last_stamp = stamp;
   }
   if (!noData) last_time_str = timeStr;
   if (typeof dataObj['batteryPc'] !== 'undefined') {
       battery_from_data = true;
       setBattery(dataObj['batteryPc']);
   }
   setPhoneBattery(dataObj['phoneBatteryPc']);
   if (typeof dataObj['watchSignalStrength'] !== 'undefined') setSignal(dataObj['watchSignalStrength']);
   updateAlarmTypes(dataObj);
   if (typeof dataObj['adaptiveHrAlarmActive'] !== 'undefined' &&
       typeof dataObj['averageHrAlarmActive'] !== 'undefined' &&
       typeof dataObj['watchSignalStrength'] !== 'undefined') settings_needed = false;
   var alarmState = dataObj['alarmState'];
   var alarmPhrase = dataObj['alarmPhrase'];
   var pebCon = dataObj['watchConnected'];
   var pebAppRun = dataObj['watchAppRunning'];
   $("#alarmState").html("Alarm State = "+alarmState);
   $("#alarmPhrase").html("Alarm Phrase = "+alarmPhrase);

   var faultTxt = ((alarmState == 4 || alarmState == 7) && dataObj['faultCause']) ? " - " + dataObj['faultCause'] : "";
   $("#benStat").html(statusHtml("status="+alarmPhrase+faultTxt, timeStr));

   switch(alarmState) {
       case 0:
           $("#benStat").css("background-color","blue");
           break;
       case 1: 
           $("#benStat").css("background-color","orange");
           beepMaybe();
           break;
       case 2:
           $("#benStat").css("background-color","red");
           beepMaybe();
           break;
       case 3:    // FALL
       case 5:    // MANUAL alarm
           $("#benStat").css("background-color","red");
           beepMaybe();
           break;
       case 4:    // FAULT (e.g. no data from the watch)
       case 7:    // NETFAULT
           $("#benStat").css("background-color","orange");
           beepMaybe();
           break;
       case 6:    // MUTE: audible alarms are muted, so make that visible, but stay quiet
           $("#benStat").css("background-color","orange");
           break;
       default:   // unknown state: never keep showing the previous colour
           $("#benStat").css("background-color","orange");
           break;
   }

   if (typeof alarmPhrase == 'undefined')
           $("#benStat").css("background-color","orange");

   if (noData) {
       $("#benStat").html("No data yet - waiting for the watch").css("background-color", "");   // neutral grey
   } else {
       var age = Date.now() - last_new_ms;
       if (age > STALE_AFTER_MS && alarmState != 4 && alarmState != 7) showProblem("No new data for " + Math.round(age / 1000) + " s");
   }

   switch(pebCon) {
      case false:
          $("#pebStat1").html("*** Watch Not Connected ***").css("background-color","red").show();
          break;
      case true:
          $("#pebStat1").hide();
          break;
   }

   switch(pebAppRun) {
      case false:
          $("#pebStat2").html("*** Watch App Not Running ***").css("background-color","red").show();
          break;
      case true:
          $("#pebStat2").hide();
          break;
   }
          

};

// Request spectrum from the server.
function get_spectrum() {
   $.ajax({url:"/spectrum",success:process_spectrum});
};

function process_spectrum(dataObj) {
   //var dataObj = JSON.parse(dataStr);

   var chartData = {
       labels:["1","2","3","4","5","6","7","8","9","10"],
       datasets: [
         { 
             label:"Spectrum",
             data:dataObj['simpleSpec']
         }
       ]
       };

   var chartWidth = 400;
   var chartHeight = 300;
   var ctx1 = $("#specChart1").get(0).getContext("2d");
   ctx1.canvas.width = chartWidth;
   ctx1.canvas.height = chartHeight;
   var specChart1 = new Chart(ctx1);
    //alert(JSON.stringify(chartData));
    var chartOpts = {
		     animation: false,
		     showScale: true,
		     scaleOverride: true,
		     scaleSteps: 10,
		     scaleStepWidth: 30,
		     scaleStartValue: 0
		     };
    var lineChart1 = new Chart(ctx1).Line(chartData,chartOpts);

   var ctx2 = $("#specChart2").get(0).getContext("2d");
   ctx2.canvas.width = chartWidth;
   ctx2.canvas.height = chartHeight;
   var specChart2 = new Chart(ctx2);
    var chartOpts = {
		     animation: false,
		     showScale: true,
		     scaleOverride: false,
		     };
    var lineChart2 = new Chart(ctx2).Line(chartData,chartOpts);
}


// toggle the global sd_muted flag and change button text.
toggleMute = function() {
   if (sd_muted) {
       sd_muted = 0;
       $("#muteButton").text("Mute Audible Alarm");
   }
   else {
       sd_muted = 1;
       $("#muteButton").text("Un-mute Audible Alarm");
   }
};

acceptAlarm = function() {
   $.ajax({url:"/acceptalarm"});
};


// NOTE:  Settings and spectrum retrieval disabled because we do not display those any more for simplicity.
$(document).ready(function() {  
   sd_muted = 0;
   get_settings();
   get_data();
   //get_spectrum();
   setInterval("get_data();",2000);
   setInterval("if (settings_needed) get_settings();",SETTINGS_POLL_MS);
   //setInterval("get_spectrum();",5000);
   buildAlarmTypes();
   $("#muteButton").click(toggleBrowserMute);
   setInterval("if (mute_until) updateMuteButton();",1000);
});
