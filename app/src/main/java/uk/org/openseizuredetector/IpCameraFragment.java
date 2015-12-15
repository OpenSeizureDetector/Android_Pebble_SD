package uk.org.openseizuredetector;

import android.app.Activity;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;



/**
 * A simple {@link Fragment} subclass.
 * Activities that contain this fragment must implement the
 * {@link IpCameraFragment.OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link IpCameraFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class IpCameraFragment extends Fragment
implements MediaPlayer.OnPreparedListener {
    private String TAG = "IpCameraFragment";
    private MediaPlayer mp1;
    private OnFragmentInteractionListener mListener;

    public IpCameraFragment() {
        // Required empty public constructor
    }

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment IpCameraFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static IpCameraFragment newInstance() {
        IpCameraFragment fragment = new IpCameraFragment();
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_ip_camera, container, false);
    }

    // TODO: Rename method, update argument and hook method into UI event
    public void onButtonPressed(Uri uri) {
        if (mListener != null) {
            mListener.onFragmentInteraction(uri);
        }
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        Context context = (Context)activity;
        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
        try {
            mp1 = new MediaPlayer();
            mp1.setOnPreparedListener(this);
            mp1.setDataSource("rtsp://guest:guest@192.168.1.6/play2.sdp");
        } catch (Exception e) {
            Log.v(TAG,"Error starting MediaPlayer - "+e.toString());
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    public void onPrepared(MediaPlayer mp) {
        Log.v(TAG, "onPrepared()");
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        void onFragmentInteraction(Uri uri);
    }
}

