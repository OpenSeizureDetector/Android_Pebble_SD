/*
  Android_SD - Android host for Garmin or Pebble watch based seizure detectors.
  See http://openseizuredetector.org for more information.

  Copyright Graham Jones, 2025.

  This file is part of Android_SD.

  Android_SD is free software: you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation, either version 3 of the License, or
  (at your option) any later version.

  Android_SD is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with Android_SD.  If not, see <http://www.gnu.org/licenses/>.
*/
package uk.org.openseizuredetector.utils;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * CircBufPersistenceManager provides serialization and deserialization of CircBuf objects
 * to/from JSON format for database persistence.
 *
 * This allows graph history (battery, signal strength, heart rate, ML seizure probability)
 * to be persisted to the database and restored on app restart, preserving the visual
 * history across app restarts (though it becomes "last N minutes of operation" rather
 * than "last N actual minutes" if there's a gap between restarts).
 */
public class CircBufPersistenceManager {
    private static final String TAG = "CircBufPersistence";

    /**
     * Serialize a CircBuf object to JSON format for storage in the database.
     * The JSON structure preserves the circular buffer state including:
     * - The actual buffer data array
     * - Head position (where we start reading)
     * - Tail position (where we write next)
     * - Whether buffer is full
     * - Error value indicator
     *
     * @param circBuf The CircBuf object to serialize
     * @return JSON string representing the buffer state, or null if circBuf is null
     */
    public static String serializeCircBuf(CircBuf circBuf) {
        if (circBuf == null) {
            Log.w(TAG, "serializeCircBuf: circBuf is null");
            return null;
        }

        try {
            double[] values = circBuf.getVals();
            int numVals = circBuf.getNumVals();

            //Log.v(TAG, "serializeCircBuf: Serializing buffer with " + numVals + " values, capacity=" + circBuf.getBufferLength());

            JSONObject json = new JSONObject();

            // Store the current data values
            JSONArray valuesArray = new JSONArray();
            for (double val : values) {
                valuesArray.put(val);
            }
            json.put("values", valuesArray);

            // Store metadata about buffer state
            json.put("numVals", numVals);
            json.put("totalCapacity", circBuf.getBufferLength());

            String result = json.toString();
            //Log.v(TAG, "serializeCircBuf: Serialization complete, JSON length=" + result.length());
            return result;

        } catch (JSONException e) {
            Log.e(TAG, "serializeCircBuf: Error serializing CircBuf: " + e.getMessage());
            return null;
        }
    }

    /**
     * Deserialize a CircBuf object from JSON format (previously serialized by serializeCircBuf).
     * This restores a CircBuf to the same state it was in when serialized.
     *
     * @param jsonStr JSON string containing serialized CircBuf data
     * @param bufferSize The desired size of the new CircBuf
     * @param errorValue The error value to use for this CircBuf (-1 or similar)
     * @return Populated CircBuf object, or a new empty one if deserialization fails
     */
    public static CircBuf deserializeCircBuf(String jsonStr, int bufferSize, double errorValue) {
        CircBuf circBuf = new CircBuf(bufferSize, errorValue);

        if (jsonStr == null || jsonStr.isEmpty()) {
            Log.w(TAG, "deserializeCircBuf: jsonStr is null or empty, returning empty buffer");
            return circBuf;
        }

        try {
            JSONObject json = new JSONObject(jsonStr);
            JSONArray valuesArray = json.getJSONArray("values");

            // Add all values to the circular buffer in order
            for (int i = 0; i < valuesArray.length(); i++) {
                circBuf.add(valuesArray.getDouble(i));
            }

            //Log.d(TAG, "deserializeCircBuf: deserialized " + valuesArray.length() + " values into buffer of size " + bufferSize);
            return circBuf;

        } catch (JSONException e) {
            Log.e(TAG, "deserializeCircBuf: Error deserializing CircBuf from JSON: " + e.getMessage());
            Log.w(TAG, "deserializeCircBuf: Returning empty buffer");
            return circBuf;
        }
    }
}
