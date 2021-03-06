/*
 * Copyright (C) 2012-2013 Paul Watts (paulcwatts@gmail.com) 
 * and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.joulespersecond.oba.region;

import com.joulespersecond.oba.elements.ObaRegion;
import com.joulespersecond.oba.elements.ObaRegion.Bounds;
import com.joulespersecond.oba.elements.ObaRegionElement;
import com.joulespersecond.oba.provider.ObaContract.RegionBounds;
import com.joulespersecond.oba.provider.ObaContract.Regions;
import com.joulespersecond.oba.request.ObaRegionsRequest;
import com.joulespersecond.oba.request.ObaRegionsResponse;
import com.joulespersecond.seattlebusbot.Application;
import com.joulespersecond.seattlebusbot.BuildConfig;
import com.joulespersecond.seattlebusbot.R;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.net.Uri;
import android.util.Log;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

public class RegionUtils {

    private static final String TAG = "RegionUtils";

    /**
     * Get the closest region from a list of regions and a given location
     *
     * This method also enforces the constraints in isRegionUsable() to
     * ensure the returned region is actually usable by the app
     *
     * @param regions list of regions
     * @param loc     location
     * @return the closest region to the given location from the list of regions, or null if a
     * closest region couldn't be found
     */
    public static ObaRegion getClosestRegion(ArrayList<ObaRegion> regions, Location loc) {
        if (loc == null) {
            return null;
        }
        float minDist = Float.MAX_VALUE;
        ObaRegion closestRegion = null;
        Float distToRegion;

        NumberFormat fmt = NumberFormat.getInstance();
        if (fmt instanceof DecimalFormat) {
            ((DecimalFormat) fmt).setMaximumFractionDigits(1);
        }
        double miles;

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Finding region closest to " + loc.getLatitude() + "," + loc.getLongitude());
        }

        for (ObaRegion region : regions) {
            if (!isRegionUsable(region)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Excluding '" + region.getName()
                            + "' from 'closest region' consideration");
                }
                continue;
            }

            distToRegion = getDistanceAway(region, loc.getLatitude(), loc.getLongitude());
            if (distToRegion == null) {
                Log.e(TAG, "Couldn't measure distance to region '" + region.getName() + "'");
                continue;
            }
            miles = distToRegion * 0.000621371;
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Region '" + region.getName() + "' is " + fmt.format(miles)
                        + " miles away");
            }
            if (distToRegion < minDist) {
                closestRegion = region;
                minDist = distToRegion;
            }
        }

        return closestRegion;
    }

    /**
     * Returns the distance from the specified location
     * to the center of the closest bound in this region.
     *
     * @return distance from the specified location to the center of the closest bound in this
     * region, in meters
     */
    public static Float getDistanceAway(ObaRegion region, double lat, double lon) {
        Bounds[] bounds = region.getBounds();
        if (bounds == null) {
            return null;
        }
        float[] results = new float[1];
        float minDistance = Float.MAX_VALUE;
        for (Bounds bound : bounds) {
            Location.distanceBetween(lat, lon, bound.getLat(), bound.getLon(), results);
            if (results[0] < minDistance) {
                minDistance = results[0];
            }
        }
        return minDistance;
    }

    public static Float getDistanceAway(ObaRegion region, Location loc) {
        return getDistanceAway(region, loc.getLatitude(), loc.getLongitude());
    }

    /**
     * Returns the center and lat/lon span for the entire region.
     *
     * @param results Array to receive results.
     *                results[0] == latSpan of region
     *                results[1] == lonSpan of region
     *                results[2] == lat center of region
     *                results[3] == lon center of region
     */
    public static void getRegionSpan(ObaRegion region, double[] results) {
        if (results.length < 4) {
            throw new IllegalArgumentException("Results array is < 4");
        }
        if (region == null) {
            throw new IllegalArgumentException("Region is null");
        }
        double latMin = 90;
        double latMax = -90;
        double lonMin = 180;
        double lonMax = -180;

        // This is fairly simplistic
        for (Bounds bound : region.getBounds()) {
            // Get the top bound
            double lat = bound.getLat();
            double latSpanHalf = bound.getLatSpan() / 2.0;
            double lat1 = lat - latSpanHalf;
            double lat2 = lat + latSpanHalf;
            if (lat1 < latMin) {
                latMin = lat1;
            }
            if (lat2 > latMax) {
                latMax = lat2;
            }

            double lon = bound.getLon();
            double lonSpanHalf = bound.getLonSpan() / 2.0;
            double lon1 = lon - lonSpanHalf;
            double lon2 = lon + lonSpanHalf;
            if (lon1 < lonMin) {
                lonMin = lon1;
            }
            if (lon2 > lonMax) {
                lonMax = lon2;
            }
        }

        results[0] = latMax - latMin;
        results[1] = lonMax - lonMin;
        results[2] = latMin + ((latMax - latMin) / 2.0);
        results[3] = lonMin + ((lonMax - lonMin) / 2.0);
    }

    /**
     * Determines if the provided location is within the provided region span
     *
     * Note: This does not handle cases when the region span crosses the
     * International Date Line properly
     *
     * @param location   that will be compared to the provided regionSpan
     * @param regionSpan span information for the region
     *                   regionSpan[0] == latSpan of region
     *                   regionSpan[1] == lonSpan of region
     *                   regionSpan[2] == lat center of region
     *                   regionSpan[3] == lon center of region
     * @return true if the location is within the region span, false if it is not
     */
    public static boolean isLocationWithinRegion(Location location, double[] regionSpan) {
        if (regionSpan == null || regionSpan.length < 4) {
            throw new IllegalArgumentException("regionSpan is null or has length < 4");
        }

        if (location == null || location.getLongitude() > 180.0 || location.getLongitude() < -180.0
                ||
                location.getLatitude() > 90 || location.getLatitude() < -90) {
            throw new IllegalArgumentException("Location must be a valid location");
        }

        double minLat = regionSpan[2] - (regionSpan[0] / 2);
        double minLon = regionSpan[3] - (regionSpan[1] / 2);
        double maxLat = regionSpan[2] + (regionSpan[0] / 2);
        double maxLon = regionSpan[3] + (regionSpan[1] / 2);

        return minLat <= location.getLatitude() && location.getLatitude() <= maxLat
                && minLon <= location.getLongitude() && location.getLongitude() <= maxLon;
    }

    /**
     * Determines if the provided location is within the provided region
     *
     * Note: This does not handle cases when the region span crosses the
     * International Date Line properly
     *
     * @param location that will be compared to the provided region
     * @param region   provided region
     * @return true if the location is within the region, false if it is not
     */
    public static boolean isLocationWithinRegion(Location location, ObaRegion region) {
        double[] regionSpan = new double[4];
        getRegionSpan(region, regionSpan);
        return isLocationWithinRegion(location, regionSpan);
    }

    /**
     * Checks if the given region is usable by the app, based on what this app supports
     * - Is the region active?
     * - Does the region support the OBA Discovery APIs?
     * - Does the region support the OBA Realtime APIs?
     * - Is the region experimental, and if so, did the user opt-in via preferences?
     *
     * @param region region to be checked
     * @return true if the region is usable by this application, false if it is not
     */
    public static boolean isRegionUsable(ObaRegion region) {
        if (!region.getActive()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Region '" + region.getName() + "' is not active.");
            }
            return false;
        }
        if (!region.getSupportsObaDiscoveryApis()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG,
                        "Region '" + region.getName() + "' does not support OBA Discovery APIs.");
            }
            return false;
        }
        if (!region.getSupportsObaRealtimeApis()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Region '" + region.getName() + "' does not support OBA Realtime APIs.");
            }
            return false;
        }
        if (region.getExperimental() && !Application.getPrefs().getBoolean(
                Application.get().getString(R.string.preference_key_experimental_regions), false)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Region '" + region.getName()
                        + "' is experimental and user hasn't opted in.");
            }
            return false;
        }

        return true;
    }

    /**
     * Gets regions from either the server, local provider, or if both fails the regions file
     * packaged
     * with the APK.  Includes fail-over logic to prefer sources in above order, with server being
     * the first preference.
     *
     * @param forceReload true if a reload from the server should be forced, false if it should not
     * @return a list of regions from either the server, the local provider, or the packaged
     * resource file
     */
    public synchronized static ArrayList<ObaRegion> getRegions(Context context,
            boolean forceReload) {
        ArrayList<ObaRegion> results;
        if (!forceReload) {
            //
            // Check the DB
            //
            results = RegionUtils.getRegionsFromProvider(context);
            if (results != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Retrieved regions from database.");
                }
                return results;
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Regions list retrieved from database was null.");
            }
        }

        results = RegionUtils.getRegionsFromServer(context);
        if (results == null || results.isEmpty()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Regions list retrieved from server was null or empty.");
            }

            if (forceReload) {
                //If we tried to force a reload from the server, then we haven't tried to reload from local provider yet
                results = RegionUtils.getRegionsFromProvider(context);
                if (results != null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Retrieved regions from database.");
                    }
                    return results;
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Regions list retrieved from database was null.");
                    }
                }
            }

            //If we reach this point, the call to the Regions REST API failed and no results were
            //available locally from a prior server request.        
            //Fetch regions from local resource file as last resort (otherwise user can't use app)
            results = RegionUtils.getRegionsFromResources(context);

            if (results == null) {
                //This is a complete failure to load region info from all sources, app will be useless
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Regions list retrieved from local resource file was null.");
                }
                return results;
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Retrieved regions from local resource file.");
            }
        } else {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Retrieved regions list from server.");
            }
            //Update local time for when the last region info was retrieved from the server
            Application.get().setLastRegionUpdateDate(new Date().getTime());
        }

        //If the region info came from the server or local resource file, we need to save it to the local provider
        RegionUtils.saveToProvider(context, results);
        return results;
    }

    public static ArrayList<ObaRegion> getRegionsFromProvider(Context context) {
        // Prefetch the bounds to limit the number of DB calls.
        HashMap<Long, ArrayList<ObaRegionElement.Bounds>> allBounds = getBoundsFromProvider(
                context);

        Cursor c = null;
        try {
            final String[] PROJECTION = {
                    Regions._ID,
                    Regions.NAME,
                    Regions.OBA_BASE_URL,
                    Regions.SIRI_BASE_URL,
                    Regions.LANGUAGE,
                    Regions.CONTACT_EMAIL,
                    Regions.SUPPORTS_OBA_DISCOVERY,
                    Regions.SUPPORTS_OBA_REALTIME,
                    Regions.SUPPORTS_SIRI_REALTIME,
                    Regions.TWITTER_URL,
                    Regions.EXPERIMENTAL
            };

            ContentResolver cr = context.getContentResolver();
            c = cr.query(Regions.CONTENT_URI, PROJECTION, null, null, Regions._ID);
            if (c == null) {
                return null;
            }
            if (c.getCount() == 0) {
                c.close();
                return null;
            }
            ArrayList<ObaRegion> results = new ArrayList<ObaRegion>();

            c.moveToFirst();
            do {
                long id = c.getLong(0);
                ArrayList<ObaRegionElement.Bounds> bounds = allBounds.get(id);
                ObaRegionElement.Bounds[] bounds2 = (bounds != null) ?
                        bounds.toArray(new ObaRegionElement.Bounds[]{}) :
                        null;

                results.add(new ObaRegionElement(id,   // id
                        c.getString(1),             // Name
                        true,                       // Active
                        c.getString(2),             // OBA Base URL
                        c.getString(3),             // SIRI Base URL
                        bounds2,                    // Bounds
                        c.getString(4),             // Lang
                        c.getString(5),             // Contact Email
                        c.getInt(6) > 0,            // Supports Oba Discovery
                        c.getInt(7) > 0,            // Supports Oba Realtime
                        c.getInt(8) > 0,            // Supports Siri Realtime
                        c.getString(9),              // Twitter URL
                        c.getInt(10) > 0            // Experimental
                ));

            } while (c.moveToNext());

            return results;

        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private static HashMap<Long, ArrayList<ObaRegionElement.Bounds>> getBoundsFromProvider(
            Context context) {
        // Prefetch the bounds to limit the number of DB calls.
        Cursor c = null;
        try {
            final String[] PROJECTION = {
                    RegionBounds.REGION_ID,
                    RegionBounds.LATITUDE,
                    RegionBounds.LONGITUDE,
                    RegionBounds.LAT_SPAN,
                    RegionBounds.LON_SPAN
            };
            HashMap<Long, ArrayList<ObaRegionElement.Bounds>> results
                    = new HashMap<Long, ArrayList<ObaRegionElement.Bounds>>();

            ContentResolver cr = context.getContentResolver();
            c = cr.query(RegionBounds.CONTENT_URI, PROJECTION, null, null, null);
            if (c == null) {
                return results;
            }
            if (c.getCount() == 0) {
                c.close();
                return results;
            }
            c.moveToFirst();
            do {
                long regionId = c.getLong(0);
                ArrayList<ObaRegionElement.Bounds> bounds = results.get(regionId);
                ObaRegionElement.Bounds b = new ObaRegionElement.Bounds(
                        c.getDouble(1),
                        c.getDouble(2),
                        c.getDouble(3),
                        c.getDouble(4));
                if (bounds != null) {
                    bounds.add(b);
                } else {
                    bounds = new ArrayList<ObaRegionElement.Bounds>();
                    bounds.add(b);
                    results.put(regionId, bounds);
                }

            } while (c.moveToNext());

            return results;

        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private synchronized static ArrayList<ObaRegion> getRegionsFromServer(Context context) {
        ObaRegionsResponse response = ObaRegionsRequest.newRequest(context).call();
        return new ArrayList<ObaRegion>(Arrays.asList(response.getRegions()));
    }

    /**
     * Retrieves region information from a regions file bundled within the app APK
     *
     * IMPORTANT - this should be a last resort, and we should always try to pull regions
     * info from the local provider or Regions REST API instead of from the bundled file.
     *
     * This method is only intended to be a fail-safe in case the Regions REST API goes
     * offline and a user downloads and installs OBA Android during that period
     * (i.e., local OBA servers are available, but Regions REST API failure would block initial
     * execution of the app).  This avoids a potential central point of failure for OBA
     * Android installations on devices in multiple regions.
     *
     * @return list of regions retrieved from the regions file in app resources
     */
    public static ArrayList<ObaRegion> getRegionsFromResources(Context context) {
        final Uri.Builder builder = new Uri.Builder();
        builder.scheme(ContentResolver.SCHEME_ANDROID_RESOURCE);
        builder.authority(context.getPackageName());
        builder.path(Integer.toString(R.raw.regions_v3));
        ObaRegionsResponse response = ObaRegionsRequest.newRequest(context, builder.build()).call();
        return new ArrayList<ObaRegion>(Arrays.asList(response.getRegions()));
    }

    //
    // Saving
    //
    public synchronized static void saveToProvider(Context context, ArrayList<ObaRegion> regions) {
        // Delete all the existing regions
        ContentResolver cr = context.getContentResolver();
        cr.delete(Regions.CONTENT_URI, null, null);
        // Should be a no-op?
        cr.delete(RegionBounds.CONTENT_URI, null, null);

        for (ObaRegion region : regions) {
            if (!isRegionUsable(region)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Skipping insert of '" + region.getName() + "' to provider...");
                }
                continue;
            }

            cr.insert(Regions.CONTENT_URI, toContentValues(region));
            //TODO - We need to save the current date/time along with region info, so later we can refresh based on elapsed time
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Saved region '" + region.getName() + "' to provider");
            }
            long regionId = region.getId();
            // Bulk insert the bounds
            ObaRegion.Bounds[] bounds = region.getBounds();
            if (bounds != null) {
                ContentValues[] values = new ContentValues[bounds.length];
                for (int i = 0; i < bounds.length; ++i) {
                    values[i] = toContentValues(regionId, bounds[i]);
                }
                cr.bulkInsert(RegionBounds.CONTENT_URI, values);
            }
        }
    }

    private static ContentValues toContentValues(ObaRegion region) {
        ContentValues values = new ContentValues();
        values.put(Regions._ID, region.getId());
        values.put(Regions.NAME, region.getName());
        String obaUrl = region.getObaBaseUrl();
        values.put(Regions.OBA_BASE_URL, obaUrl != null ? obaUrl : "");
        String siriUrl = region.getSiriBaseUrl();
        values.put(Regions.SIRI_BASE_URL, siriUrl != null ? siriUrl : "");
        values.put(Regions.LANGUAGE, region.getLanguage());
        values.put(Regions.CONTACT_EMAIL, region.getContactEmail());
        values.put(Regions.SUPPORTS_OBA_DISCOVERY, region.getSupportsObaDiscoveryApis() ? 1 : 0);
        values.put(Regions.SUPPORTS_OBA_REALTIME, region.getSupportsObaRealtimeApis() ? 1 : 0);
        values.put(Regions.SUPPORTS_SIRI_REALTIME, region.getSupportsSiriRealtimeApis() ? 1 : 0);
        values.put(Regions.TWITTER_URL, region.getTwitterUrl());
        values.put(Regions.EXPERIMENTAL, region.getExperimental());
        return values;
    }

    private static ContentValues toContentValues(long region, ObaRegion.Bounds bounds) {
        ContentValues values = new ContentValues();
        values.put(RegionBounds.REGION_ID, region);
        values.put(RegionBounds.LATITUDE, bounds.getLat());
        values.put(RegionBounds.LONGITUDE, bounds.getLon());
        values.put(RegionBounds.LAT_SPAN, bounds.getLatSpan());
        values.put(RegionBounds.LON_SPAN, bounds.getLonSpan());
        return values;
    }
}
