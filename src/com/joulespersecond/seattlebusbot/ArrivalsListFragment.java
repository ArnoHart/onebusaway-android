package com.joulespersecond.seattlebusbot;

import com.joulespersecond.oba.ObaApi;
import com.joulespersecond.oba.elements.ObaArrivalInfo;
import com.joulespersecond.oba.elements.ObaStop;
import com.joulespersecond.oba.provider.ObaContract;
import com.joulespersecond.oba.request.ObaArrivalInfoResponse;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.Loader;
import android.support.v4.view.Menu;
import android.support.v4.view.MenuItem;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.util.ArrayList;

//
// We don't use the ListFragment because the support library's version of
// the ListFragment doesn't work well with our header.
//
public class ArrivalsListFragment extends MyListFragment
        implements LoaderManager.LoaderCallbacks<ObaArrivalInfoResponse>,
                   ArrivalsListHeader.Controller {
    private static final String TAG = "ArrivalsListFragment";
    //private static final long RefreshPeriod = 60 * 1000;

    //private static int TRIPS_FOR_STOP_LOADER = 1;
    private static int ARRIVALS_LIST_LOADER = 2;

    private ArrivalsListAdapter mAdapter;
    private ArrivalsListHeader mHeader;

    private ObaStop mStop;
    private String mStopId;
    private Uri mStopUri;
    private ArrayList<String> mRoutesFilter;

    private boolean mFavorite = false;
    private String mStopUserName;

    // Used by the test code to signal when we've retrieved stops.
    private Object mStopWait;

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        // Give some text to display if there is no data.  In a real
        // application this would come from a resource.
        setEmptyText(getString(R.string.stop_info_nodata));

        // We have a menu item to show in action bar.
        setHasOptionsMenu(true);

        mHeader = new ArrivalsListHeader(getActivity(), this);
        View header = getView().findViewById(R.id.arrivals_list_header);
        mHeader.initView(header);
        mHeader.refresh();

        // This sets the stopId and uri
        setStopId();
        setUserInfo();

        // Create an empty adapter we will use to display the loaded data.
        mAdapter = new ArrivalsListAdapter(getActivity());
        setListAdapter(mAdapter);

        // Start out with a progress indicator.
        setListShown(false);

        mRoutesFilter = ObaContract.StopRouteFilters.get(getActivity(), mStopId);
        //mTripsForStop = getTripsForStop();

        //LoaderManager.enableDebugLogging(true);

        // First load the trips for stop map. When this is finished, we load
        // the arrivals info.
        //LoaderManager mgr = getLoaderManager();
        //Loader<Cursor> loader = mgr.initLoader(TRIPS_FOR_STOP_LOADER, null, new TripsForStopCallback());
        //loader.forceLoad();

        getLoaderManager().initLoader(ARRIVALS_LIST_LOADER, getArguments(), this);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
            ViewGroup root, Bundle savedInstanceState) {
        if (root == null) {
            // Currently in a layout without a container, so no
            // reason to create our view.
            return null;
        }
        return inflater.inflate(R.layout.fragment_arrivals_list, null);
    }

    /*
    @Override
    public void onPause() {
        mTripsForStop.setKeepUpdated(false);
        mRefreshHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    public void onResume() {
        mTripsForStop.setKeepUpdated(true);
        mTripsForStop.requery();
        ((BaseAdapter)getListAdapter()).notifyDataSetChanged();

        // If our timer would have gone off, then refresh.
        long newPeriod = Math.min(RefreshPeriod, (mResponseTime + RefreshPeriod)
                - System.currentTimeMillis());
        // Wait at least one second at least, and the full minute at most.
        //Log.d(TAG, "Refresh period:" + newPeriod);
        if (newPeriod <= 0) {
            getStopInfo(true);
        } else {
            mRefreshHandler.postDelayed(mRefresh, newPeriod);
        }

        super.onResume();
    }
    */

    @Override
    public Loader<ObaArrivalInfoResponse> onCreateLoader(int id, Bundle args) {
        return new ArrivalsListLoader(getActivity(), mStopId);
    }

    //
    // This is where the bulk of the initialization takes place to create
    // this screen.
    //
    @Override
    public void onLoadFinished(Loader<ObaArrivalInfoResponse> loader,
            ObaArrivalInfoResponse result) {
        Log.d(TAG, "Load finished!");

        ObaArrivalInfo[] info = null;

        if (result.getCode() == ObaApi.OBA_OK) {
            if (mStop == null) {
                mStop = result.getStop();
                addToDB(mStop);
            }
            info = result.getArrivalInfo();

        } else {
            // If there was a last good response, then this is a refresh
            // and we should use a toast. Otherwise, it's a initial
            // page load and we want to display the error in the empty text.
            ObaArrivalInfoResponse lastGood = getLastGoodResponse();
            if (lastGood != null) {
                // Refresh error
                Toast.makeText(getActivity(),
                        R.string.generic_comm_error_toast,
                        Toast.LENGTH_LONG).show();
                info = lastGood.getArrivalInfo();

            } else {
                setEmptyText(getString(UIHelp.getStopErrorString(result.getCode())));
            }
        }

        mHeader.refresh();

        if (info != null) {
            // Reset the empty text just in case there is no data.
            setEmptyText(getString(R.string.stop_info_nodata));
            ArrayList<ArrivalInfo> list =
                    ArrivalInfo.convertObaArrivalInfo(getActivity(),
                            result.getArrivalInfo(), mRoutesFilter);
            mAdapter.setData(list);
        }

        // The list should now be shown.
        if (isResumed()) {
            setListShown(true);
        } else {
            setListShownNoAnimation(true);
        }

        if (mStopWait != null) {
            synchronized (mStopWait) {
                mStopWait.notifyAll();
            }
        }
    }

    @Override
    public void onLoaderReset(Loader<ObaArrivalInfoResponse> loader) {
        mAdapter.setData(null);
    }

    //
    // Action Bar / Options Menu
    //
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.arrivals_list, menu);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.toggle_favorite)
            .setTitle(mFavorite ?
                    R.string.stop_info_option_removestar :
                    R.string.stop_info_option_addstar);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int id = item.getItemId();
        if (id == R.id.show_on_map) {
            /*
            if (mResponse != null) {
                MapViewActivity.start(this, mStopId, mStop.getLatitude(), mStop.getLongitude());
            }
            */
            return true;
        } else if (id == R.id.refresh) {
            //getStopInfo(true);
            return true;
        } else if (id == R.id.filter) {
            /*
            if (mResponse != null) {
                showRoutesFilterDialog();
            }
            */
        } else if (id == R.id.edit_name) {
            mHeader.beginNameEdit(null);
        } else if (id == R.id.toggle_favorite) {
            //toggleFavorite();
        }
        return false;
    }

    //
    // ActivityListHeader.Controller
    //
    @Override
    public String getStopName() {
        String name;
        if (mStop != null) {
            name = mStop.getName();
        } else {
            // Check the arguments
            Bundle args = getArguments();
            name = args.getString(ArrivalsListActivity.STOP_NAME);
        }
        return MyTextUtils.toTitleCase(name);
    }

    @Override
    public String getStopDirection() {
        if (mStop != null) {
            return mStop.getDirection();
        } else {
            // Check the arguments
            Bundle args = getArguments();
            return args.getString(ArrivalsListActivity.STOP_DIRECTION);
        }
    }

    @Override
    public String getUserStopName() {
        return mStopUserName;
    }

    @Override
    public void setUserStopName(String name) {
        ContentResolver cr = getActivity().getContentResolver();
        ContentValues values = new ContentValues();
        if (TextUtils.isEmpty(name)) {
            values.putNull(ObaContract.Stops.USER_NAME);
            mStopUserName = null;
        } else {
            values.put(ObaContract.Stops.USER_NAME, name);
            mStopUserName = name;
        }
        cr.update(mStopUri, values, null, null);
    }

    @Override
    public ArrayList<String> getRoutesFilter() {
        return mRoutesFilter;
    }

    @Override
    public void setRoutesFilter(ArrayList<String> routes) {
        // TODO:
        /*
        mRoutesFilter = routes;
        ObaContract.StopRouteFilters.set(getActivity(), mStopId, mRoutesFilter);
        StopInfoListAdapter adapter = (StopInfoListAdapter) getListView().getAdapter();
        adapter.setData(mResponse.getArrivalInfo());
        */
    }

    @Override
    public long getLastGoodResponseTime() {
        Loader<ObaArrivalInfoResponse> l =
                getLoaderManager().getLoader(ARRIVALS_LIST_LOADER);
        // Special case in onActivityCreated() when we haven't even
        // created the loader yet..
        if (l == null) {
            return 0;
        }
        ArrivalsListLoader loader = (ArrivalsListLoader)l;
        return loader.getLastGoodResponseTime();
    }

    @Override
    public int getNumRoutes() {
        return mStop.getRouteIds().length;
    }

    @Override
    public boolean isFavorite() {
        return mFavorite;
    }

    @Override
    public boolean setFavorite(boolean favorite) {
        // TODO Auto-generated method stub
        return false;
    }

    //
    // Helpers
    //

    private void setStopId() {
        Uri uri = (Uri)getArguments().getParcelable("uri");
        if (uri == null) {
            Log.e(TAG, "No URI in arguments");
            return;
        }
        mStopId = uri.getLastPathSegment();
        mStopUri = uri;
    }

    private static final String[] USER_PROJECTION = {
        ObaContract.Stops.FAVORITE,
        ObaContract.Stops.USER_NAME
    };

    private void setUserInfo() {
        ContentResolver cr = getActivity().getContentResolver();
        Cursor c = cr.query(mStopUri, USER_PROJECTION, null, null, null);
        if (c != null) {
            try {
                if (c.moveToNext()) {
                    mFavorite = (c.getInt(0) == 1);
                    mStopUserName = c.getString(1);
                }
            } finally {
                c.close();
            }
        }
    }

    //
    // Helper to get the response
    //
    private ObaArrivalInfoResponse getLastGoodResponse() {
        Loader<ObaArrivalInfoResponse> l =
                getLoaderManager().getLoader(ARRIVALS_LIST_LOADER);
        ArrivalsListLoader loader = (ArrivalsListLoader)l;
        return loader.getLastGoodResponse();
    }

    public void setStopWait(Object obj) {
        mStopWait = obj;
    }

    private void addToDB(ObaStop stop) {
        String name = MyTextUtils.toTitleCase(stop.getName());

        // Update the database
        ContentValues values = new ContentValues();
        values.put(ObaContract.Stops.CODE, stop.getStopCode());
        values.put(ObaContract.Stops.NAME, name);
        values.put(ObaContract.Stops.DIRECTION, stop.getDirection());
        values.put(ObaContract.Stops.LATITUDE, stop.getLatitude());
        values.put(ObaContract.Stops.LONGITUDE, stop.getLongitude());
        ObaContract.Stops.insertOrUpdate(getActivity(), stop.getId(), values, true);
    }

    /*
    private static final String[] TRIPS_PROJECTION = {
        ObaContract.Trips._ID, ObaContract.Trips.NAME
    };

    //
    // The asynchronously loads the trips for stop list.
    //
    private class TripsForStopCallback
            implements LoaderManager.LoaderCallbacks<Cursor> {

        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return new CursorLoader(getActivity(),
                            ObaContract.Trips.CONTENT_URI,
                            TRIPS_PROJECTION,
                            ObaContract.Trips.STOP_ID + "=?",
                            new String[] { mStopId },
                            null);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor c) {
            ContentQueryMap map =
                    new ContentQueryMap(c, ObaContract.Trips._ID, true, null);
            // Call back into the fragment and say we've finished this.
            mAdapter.setTripsForStop(map);
        }

        @Override
        public void onLoaderReset(Loader<Cursor> loader) {
        }
    }
    */

}