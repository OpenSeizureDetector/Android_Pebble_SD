package uk.org.openseizuredetector;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Bundle;
import android.util.Log;

public class MainActivity2 extends AppCompatActivity {
    private String TAG = "MainActivity2";
    private ViewPager2 mFragmentPager;
    private FragmentStateAdapter mFragmentStateAdapter;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main2);
        if (savedInstanceState == null) {
            // Instantiate a ViewPager2 and a PagerAdapter.
            mFragmentPager = findViewById(R.id.fragment_pager);
            mFragmentStateAdapter = new ScreenSlideFragmentPagerAdapter(this);
            mFragmentPager.setAdapter(mFragmentStateAdapter);
            //getSupportFragmentManager().beginTransaction()
            //        .setReorderingAllowed(true)
            //        .add(R.id.fragment_container_view, FragmentOsdAlg.class, null)
            //        .commit();
        }
    }

    @Override
    public void onBackPressed() {
        if (mFragmentPager.getCurrentItem() == 0) {
            // If the user is currently looking at the first step, allow the system to handle the
            // Back button. This calls finish() on this activity and pops the back stack.
            super.onBackPressed();
        } else {
            // Otherwise, select the previous step.
            mFragmentPager.setCurrentItem(mFragmentPager.getCurrentItem() - 1);
        }
    }

    /**
     * A simple pager adapter that represents 5 ScreenSlidePageFragment objects, in
     * sequence.
     */
    private class ScreenSlideFragmentPagerAdapter extends FragmentStateAdapter {
        private String TAG = "ScreenSlideFragmentPagerAdapter";
        public ScreenSlideFragmentPagerAdapter(FragmentActivity fa) {
            super(fa);
        }

        @Override
        public Fragment createFragment(int position) {
            switch(position) {
                case 0:
                    return new FragmentOsdAlg();
                case 1:
                    return new FragmentHrAlg();
                default:
                    Log.e(TAG,"createFragment() - invalid Position "+position);
                    return null;
            }
        }

        @Override
        public int getItemCount() {
            return 2;
        }
    }
}