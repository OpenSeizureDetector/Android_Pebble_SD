# Log Analysis Guide - Quick Reference

## Quick Commands to Analyze Logs

### 1. Extract Overnight Logs
```bash
# Pull logs from device
adb pull /storage/emulated/0/Android/data/uk.org.openseizuredetector/files/logs/ ./osd_logs/

# List available log files
ls -la osd_logs/
```

### 2. Find Shutdown Events
```bash
# Find all shutdown events
grep SHUTDOWN osd_log_2026-02-05.txt

# Show shutdown events with context (2 lines before/after)
grep -B2 -A2 SHUTDOWN osd_log_2026-02-05.txt
```

### 3. Analyze Memory Trend
```bash
# Extract all memory logs for analysis
grep "MEMORY" osd_log_2026-02-05.txt > memory_trend.txt

# Show memory trend with timestamps
grep "MEMORY.*Watchdog" osd_log_2026-02-05.txt | awk '{print $1, $2, $NF}' > memory_timeline.txt

# Show just used memory values
grep "MEMORY.*Watchdog" osd_log_2026-02-05.txt | grep -oP 'Used=\d+MB' > memory_usage.txt
```

### 4. Detect Memory Leaks
```bash
# Extract Used memory values in chronological order
grep "MEMORY.*Watchdog" osd_log_2026-02-05.txt | \
  grep -oP 'Used=\d+' | \
  sed 's/Used=//' > mem_values.txt

# Display in time order with values
grep "MEMORY.*Watchdog" osd_log_2026-02-05.txt | \
  sed 's/.*Used=//' | \
  awk '{print $1}' | \
  sort -n | \
  uniq -c
```

Example output showing memory leak:
```
Used=35MB (08:00)
Used=38MB (08:01)
Used=41MB (08:02)
Used=45MB (08:03)
Used=48MB (08:04)
...
Used=95MB (08:30)  ← Memory continues to grow unbounded
```

### 5. Find Exceptions
```bash
# Find all exceptions
grep EXCEPTION osd_log_2026-02-05.txt

# Find exceptions from specific component
grep EXCEPTION osd_log_2026-02-05.txt | grep FaultTimer

# Show exception with full stack trace (5 lines)
grep -A5 EXCEPTION osd_log_2026-02-05.txt
```

### 6. Track Specific Component Lifecycle
```bash
# Track MainActivity2 lifecycle
grep "MainActivity2" osd_log_2026-02-05.txt

# Track SdServer lifecycle
grep "SdServer" osd_log_2026-02-05.txt

# Track SdDataSourceBLE2 lifecycle
grep "SdDataSourceBLE2" osd_log_2026-02-05.txt
```

### 7. Find When System Became Unresponsive
```bash
# Show last 20 log entries before shutdown
head -n -20 osd_log_2026-02-05.txt | tail -20

# Show last heartbeat before shutdown
grep WATCHDOG osd_log_2026-02-05.txt | tail -5

# If watchdog stops, the system crashed (no more WATCHDOG entries)
grep -c WATCHDOG osd_log_2026-02-05.txt  # Shows count
```

### 8. Check App Start Sequence
```bash
# Show app startup sequence
grep -E "StartupActivity|SdServer|MainActivity2" osd_log_2026-02-05.txt | head -20

# Show memory at each startup step
grep -E "StartupActivity|SdServer|MainActivity2" osd_log_2026-02-05.txt | \
  grep LIFECYCLE
```

### 9. Analyze Data Reception
```bash
# Check for DATA_RX log entries (if added)
grep DATA_RX osd_log_2026-02-05.txt

# Check watchdog data timeout messages
grep "No data for" osd_log_2026-02-05.txt

# Shows when watchdog had to force fault due to no data
```

### 10. Low Memory Situations
```bash
# Find when LowMemory flag was set
grep "LowMemory=true" osd_log_2026-02-05.txt

# Shows low memory situations that could cause forced shutdown
```

## Automated Log Analysis Script

Save this as `analyze_logs.sh`:

```bash
#!/bin/bash
# OSD Log Analyzer

if [ -z "$1" ]; then
    echo "Usage: $0 osd_log_YYYY-MM-DD.txt"
    exit 1
fi

LOG_FILE="$1"
REPORT="analysis_report.txt"

echo "=== OSD Log Analysis Report ===" > $REPORT
echo "Log file: $LOG_FILE" >> $REPORT
echo "Generated: $(date)" >> $REPORT
echo "" >> $REPORT

echo "=== Shutdown Events ===" >> $REPORT
grep SHUTDOWN "$LOG_FILE" >> $REPORT
echo "" >> $REPORT

echo "=== Exception Events ===" >> $REPORT
grep EXCEPTION "$LOG_FILE" >> $REPORT
echo "" >> $REPORT

echo "=== Memory Statistics ===" >> $REPORT
echo "Min memory used:" >> $REPORT
grep "MEMORY.*Watchdog" "$LOG_FILE" | grep -oP 'Used=\d+' | sort -n | head -1 >> $REPORT
echo "Max memory used:" >> $REPORT
grep "MEMORY.*Watchdog" "$LOG_FILE" | grep -oP 'Used=\d+' | sort -n | tail -1 >> $REPORT
echo "Memory entries count:" >> $REPORT
grep -c "MEMORY.*Watchdog" "$LOG_FILE" >> $REPORT
echo "" >> $REPORT

echo "=== Watchdog Status ===" >> $REPORT
echo "Total heartbeats:" >> $REPORT
grep -c "WATCHDOG_HB" "$LOG_FILE" >> $REPORT
echo "Fault triggers:" >> $REPORT
grep -c "WATCHDOG_FAULT" "$LOG_FILE" >> $REPORT
echo "Last watchdog entry:" >> $REPORT
grep "WATCHDOG" "$LOG_FILE" | tail -1 >> $REPORT
echo "" >> $REPORT

echo "=== Lifecycle Events ===" >> $REPORT
grep "LIFECYCLE" "$LOG_FILE" | wc -l >> $REPORT
echo "lifecycle entries logged" >> $REPORT
echo "" >> $REPORT

echo "=== Activity Timeline ===" >> $REPORT
grep -E "LIFECYCLE.*Activity" "$LOG_FILE" >> $REPORT
echo "" >> $REPORT

echo "Report saved to: $REPORT"
cat $REPORT
```

Usage:
```bash
chmod +x analyze_logs.sh
./analyze_logs.sh osd_log_2026-02-05.txt
```

## Interpreting Memory Trends

### Healthy Memory Pattern:
```
08:00:13 Used=35MB (startup)
08:01:26 Used=38MB (settled)
08:02:26 Used=39MB (stable)
08:03:26 Used=39MB (steady)
```
✅ Memory stable after initial startup spike

### Memory Leak Pattern:
```
08:00:13 Used=35MB (startup)
08:15:26 Used=52MB (growing)
08:30:26 Used=69MB (growing)
08:45:26 Used=87MB (growing)
09:00:26 Used=105MB (approaching limit)
```
⚠️ Continuous growth = memory leak

### Sudden Spike Pattern:
```
08:00:13 Used=35MB (normal)
08:15:26 Used=39MB (normal)
08:16:01 Used=145MB (sudden)
SHUTDOWN event
```
⚠️ Sudden spike before shutdown = OOM killer or memory allocation failure

## What Each Component Should Log

| Component | What to look for | What indicates problems |
|-----------|------------------|------------------------|
| StartupActivity | Lifecycle events | No onCreate or multiple onCreate events |
| MainActivity2 | Resume/Pause events | Never onResume = app never fully started |
| SdServer | Start/Stop events | Stop without Start = abnormal termination |
| Watchdog | Regular heartbeats every 60s | No heartbeat for 2+ minutes = hang/crash |
| Memory | Gradual growth then plateau | Continuous growth = leak; Sudden spike = OOM |
| Exceptions | Should be none | Any exception = bug to fix |

## When to Share Logs for Analysis

Export entire log directory:
```bash
adb pull /storage/emulated/0/Android/data/uk.org.openseizuredetector/files/logs/ ./
tar -czf osd_logs.tar.gz logs/
# Share osd_logs.tar.gz with developers
```

Logs are anonymized and contain only app internal state, no personal data.
