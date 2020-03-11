package uk.org.openseizuredetector;


import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

public class AuthDialog extends DialogFragment {
    private String TAG = "AuthDialog";
    private AuthDialogInterface mListener;
    private Context mContext;
    private EditText mUnameEt;
    private EditText mPasswdEt;

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState) {
        Log.v(TAG, "onCreateView()");
        View v = inflater.inflate(R.layout.dialog_authenticate,
                container, false);
        Button cancelBtn =
                (Button) v.findViewById(R.id.cancelBtn);
        cancelBtn.setOnClickListener(onCancel);
        Button OKBtn = (Button) v.findViewById(R.id.OKBtn);
        OKBtn.setOnClickListener(onOK);

        mUnameEt = (EditText) v.findViewById(R.id.username);
        mPasswdEt = (EditText) v.findViewById(R.id.password);

        return v;

    }

    View.OnClickListener onCancel =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    Log.v(TAG, "onCancel");
                    //m_status=false;
                    mListener.onDialogDone(false);
                    dismiss();
                }
            };

    View.OnClickListener onOK =
            new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    //m_status=true;
                    Log.v(TAG, "onOK()");
                    String uname = mUnameEt.getText().toString();
                    String passwd = mPasswdEt.getText().toString();
                    Log.v(TAG,"onOK() - uname="+uname+", passwd="+passwd);
                    mListener.onDialogDone(true);
                    dismiss();
                }
            };

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        try {
            mListener = (AuthDialogInterface) context;
        } catch (ClassCastException e) {
            throw new ClassCastException(context.toString()
                    + " must implement dialogDoneistener");
        }
    }
}
