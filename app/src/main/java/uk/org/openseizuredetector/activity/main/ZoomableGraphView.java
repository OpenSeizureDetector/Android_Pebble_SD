package uk.org.openseizuredetector.activity.main;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;

import com.jjoe64.graphview.GraphView;

/**
 * A GraphView that can be pinched to zoom while it sits inside a vertically scrolling page.
 *
 * Without this, the page's ScrollView takes over the gesture as soon as the fingers move and
 * the graph never sees the pinch. While two or more fingers are down we tell the parents not to
 * intercept; a one-finger vertical drag still scrolls the page, and a horizontal drag pans the
 * graph when it has been zoomed in.
 *
 * Use it in layouts instead of com.jjoe64.graphview.GraphView and call
 * getViewport().setScalable(true) (done in each fragment's chart set-up).
 */
public class ZoomableGraphView extends GraphView {

    public ZoomableGraphView(Context context) {
        super(context);
    }

    public ZoomableGraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ZoomableGraphView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                setParentIntercept(false);
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() > 1) setParentIntercept(false);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setParentIntercept(true);
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    private void setParentIntercept(boolean allowed) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(!allowed);
        }
    }
}
