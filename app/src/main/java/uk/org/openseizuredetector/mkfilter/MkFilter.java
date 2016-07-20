package uk.org.openseizuredetector.mkfilter;

/**
 * A port of A.J Fisher's mkfilter utility to Java.
 * Created by graham on 12/07/16.
 */
public class MkFilter {
    private static int opt_be = 0x00001;	/* -Be		Bessel characteristic	       */
    private static int opt_bu = 0x00002;	/* -Bu		Butterworth characteristic     */
    private static int opt_ch = 0x00004;	/* -Ch		Chebyshev characteristic       */
    private static int opt_re = 0x00008;	/* -Re		Resonator		       */
    private static int opt_pi = 0x00010;	/* -Pi		proportional-integral	       */

    private static int opt_lp = 0x00020;	/* -Lp		lowpass			       */
    private static int opt_hp = 0x00040;	/* -Hp		highpass		       */
    private static int opt_bp = 0x00080;	/* -Bp		bandpass		       */
    private static int opt_bs = 0x00100;	/* -Bs		bandstop		       */
    private static int opt_ap = 0x00200;	/* -Ap		allpass			       */

    private static int opt_a = 0x00400;	/* -a		alpha value		       */
    private static int opt_l = 0x00800;	/* -l		just list filter parameters    */
    private static int opt_o = 0x01000;	/* -o		order of filter		       */
    private static int opt_p = 0x02000;	/* -p		specified poles only	       */
    private static int opt_w = 0x04000;	/* -w		don't pre-warp		       */
    private static int opt_z = 0x08000;	/* -z		use matched z-transform	       */
    private static int opt_Z = 0x10000;	/* -Z		additional zero		       */

    private static int maxpz = 25;

    private int order;

    public class complex {
        double re;
        double im;

        complex(double r, double i) {
            re = r;
            im = i;
        }
    }

    class pzrep {
        complex[] poles, zeros;
        int numpoles, numzeros;

        pzrep() {
            poles = new complex[maxpz];
            zeros = new complex[maxpz];
        }
    }


    MkFilter() {
        order = 5;
    }

    public void test() {
        pzrep pz = new pzrep();
        pz.poles[0] = new complex(1, 1);
    }


    public void compute_s() {
         /* Butterworth filter */
        for (int i = 0; i < 2 * order; i++) {
            double theta = (order & 1) ? (i * PI) / order : ((i + 0.5) * PI) / order;
            choosepole(expj(theta));
        }


    }
}
