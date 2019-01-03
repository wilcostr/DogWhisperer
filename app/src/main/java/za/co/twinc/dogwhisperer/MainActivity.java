package za.co.twinc.dogwhisperer;

import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.bumptech.glide.Glide;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.InterstitialAd;
import com.google.android.gms.ads.MobileAds;

import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import za.co.twinc.dogwhisperer.billing.BillingManager;

public class MainActivity extends AppCompatActivity{

    private final String MAIN_PREFS = "main_app_prefs";

    private BillingManager mBillingManager;
    private boolean isPremium;
    private boolean premiumPrompt;

    private ImageButton playButton;
    private TextView frequencyTextView;

    private int pauseCount;
    private int modulationType;
    private final double minf = 5000;
    private double rangef;

    private double frequency = minf;

    private Thread thread;
    private int sampleRate;
    private boolean isRunning = false;

    private SeekBar frequencySeekBar;

    private InterstitialAd mInterstitialAd;
    private AdView adView;
    private Button adButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // To get preferred buffer size and sampling rate.
        AudioManager audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);
        if (audioManager != null)
            sampleRate = Integer.parseInt(audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
        rangef = 22000 - minf;

        // Create main share preference log
        final SharedPreferences main_log = getSharedPreferences(MAIN_PREFS, 0);
        isPremium = main_log.getBoolean("premium", false);

        // Initialise ad and ad button
        adView = findViewById(R.id.adView);
        // Initialise the invisible 'why ads?' button
        adButton = findViewById(R.id.why_ads);

        // Free ad space if premium
        if (isPremium)
            adView.setVisibility(View.GONE);
        else {
            MobileAds.initialize(this, getString(R.string.app_id));
            // Load add
            AdRequest adRequest = new AdRequest.Builder()
                    .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                    .build();
            adView.loadAd(adRequest);
            adView.setAdListener(new AdListener(){
                @Override
                public void onAdLoaded(){
                    adButton.setVisibility(View.VISIBLE);
                }
            });

            // Initialise interstitial ad
            mInterstitialAd = new InterstitialAd(this);
            mInterstitialAd.setAdUnitId(getString(R.string.ad_unit_id_interstitial));
        }

        // Load dog gif
        ImageView imageView = findViewById(R.id.gif_imageview);
        Glide.with(this)
                .load("/android_asset/animated-dog.gif")
                .into(imageView);

        playButton = findViewById(R.id.play_button);

        if (Build.VERSION.SDK_INT >= 21)
            //noinspection deprecation
            playButton.setBackgroundTintList(ColorStateList.valueOf(getResources().getColor(R.color.colorAccent)));

        // Set up seekbar to change frequency
        frequencySeekBar = findViewById(R.id.frequency_seekBar);
        frequencySeekBar.setProgress(main_log.getInt("frequency",80));
        frequency = minf + main_log.getInt("frequency",80)*rangef/frequencySeekBar.getMax();

        frequencyTextView = findViewById(R.id.frequency_textView);
        frequencyTextView.setText(String.format(getString(R.string.hz),frequency*0.001));

        frequencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                if (b) {
                    frequency = minf + i*rangef/seekBar.getMax();
                    frequencyTextView.setText(String.format(getString(R.string.hz),frequency*0.001));
                    SharedPreferences.Editor editor = main_log.edit();
                    editor.putInt("frequency", i);
                    editor.apply();
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        RadioGroup modulationGroup = findViewById(R.id.modulation);
        modulationGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup radioGroup, int i) {
                setModulation(i);
            }
        });
        modulationGroup.check(main_log.getInt("modulation", R.id.modulation_none));
        setModulation(modulationGroup.getCheckedRadioButtonId());

        pauseCount = 0;
        premiumPrompt = main_log.getBoolean("premiumPrompt", false);

        // Create and initialize BillingManager which talks to BillingLibrary
        UpdateListener updateListener = new UpdateListener();
        mBillingManager = new BillingManager(this, updateListener);
    }

    @Override
    public void onPause(){
        if (isRunning && !isPremium){
            premiumPrompt = true;
            SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
            SharedPreferences.Editor editor = mainPrefs.edit();
            editor.putBoolean("premiumPrompt", true);
            editor.apply();
            stopAndDestroyThread();
            playButton.setImageResource(android.R.drawable.ic_media_play);
        }
        super.onPause();
    }

    @Override
    public void onResume(){
        if (premiumPrompt){
            // Background playback is a premium feature
            premiumPrompt = false;
            SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
            SharedPreferences.Editor editor = mainPrefs.edit();
            editor.putBoolean("premiumPrompt", false);
            editor.apply();
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.background_message);
            builder.setPositiveButton(R.string.background_button, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    mBillingManager.initiatePurchaseFlow("premium", BillingClient.SkuType.INAPP);
                }
            });
            builder.setNegativeButton(android.R.string.cancel, null);
            builder.create().show();
        }
        super.onResume();
    }

    @Override
    public void onBackPressed() {
        if (isPremium && isRunning) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getResources().getString(R.string.continue_playback_title));
            builder.setMessage(getResources().getString(R.string.continue_playback_msg));

            builder.setPositiveButton(getString(android.R.string.ok), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    moveTaskToBack(true);
                }
            });
            builder.setNegativeButton(getString(android.R.string.cancel), null);
            builder.create().show();
        }
        else
            super.onBackPressed();
    }

    public void onDestroy(){
        stopAndDestroyThread();
        if (mBillingManager != null) {
            mBillingManager.destroy();
        }
        super.onDestroy();
    }

    public void onButtonPlayClick(@SuppressWarnings("UnusedParameters") View view) {
        if (isRunning) {
            stopAndDestroyThread();
            playButton.setImageResource(android.R.drawable.ic_media_play);
            pauseCount++;
            if (!isPremium){
                if (!mInterstitialAd.isLoaded()) {
                    mInterstitialAd.loadAd(new AdRequest.Builder()
                            .addTestDevice("5F2995EE0A8305DEB4C48C77461A7362")
                            .build());
                }
            }

            if (pauseCount%3 == 0)
                showAd();
        }
        else {
            createAndStartThread();
            playButton.setImageResource(android.R.drawable.ic_media_pause);
        }
    }

    public void onButtonSeniorClick(@SuppressWarnings("UnusedParameters") View v){ presetFrequency(12000);    }
    public void onButtonAdultClick(@SuppressWarnings("UnusedParameters") View v){ presetFrequency(15000);    }
    public void onButtonKidClick(@SuppressWarnings("UnusedParameters") View v){ presetFrequency(18000);    }
    public void onButtonDogClick(@SuppressWarnings("UnusedParameters") View v){ presetFrequency(21000);    }

    private void presetFrequency(double f){
        frequencySeekBar.setProgress((int)((f-minf)*frequencySeekBar.getMax()/rangef));
        frequency = f;
        frequencyTextView.setText(String.format(getString(R.string.hz),frequency*0.001));
    }

    private void setModulation(int i){
        SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainPrefs.edit();
        editor.putInt("modulation",i);
        editor.apply();
        switch (i){
            case R.id.modulation_am:
                modulationType = 1;
                return;
            case R.id.modulation_fm:
                modulationType = 2;
                return;
            case R.id.modulation_none:
                modulationType = 0;
        }
    }

    private void showAd(){
        SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
        if (mainPrefs.getBoolean("show_feedback", true)){
            SharedPreferences.Editor editor = mainPrefs.edit();
            editor.putBoolean("show_feedback", false);
            editor.apply();
            //On first install show ad after 5 pauses.
            pauseCount++;
            feedback();
        }
        else{
            if (isPremium)
                return;
            if (mInterstitialAd.isLoaded()) {
                mInterstitialAd.show();
            }
        }
    }

    private void createAndStartThread(){
        isRunning = true;
        // Start a new thread to synthesise audio
        thread = new Thread(){
            public void run(){
                // Set process priority
                setPriority(Thread.MAX_PRIORITY);

                int bufferSize = AudioTrack.getMinBufferSize(sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);

                // Create an audiotrack object
                AudioTrack audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize,
                        AudioTrack.MODE_STREAM);

                short samples[] = new short[bufferSize];
                int amplitude = 10000;
                double twopi = 8.*Math.atan(1.0);
                double omega = 0;
                double modulation = 0;

                // Start the audio
                audioTrack.play();

                // Define synthesis loop
                while(isRunning){
                    for(int i=0; i<bufferSize; i++){
                        samples[i] = (short) (amplitude*Math.sin(omega));
                        modulation += twopi*2/sampleRate;
                        if (modulationType==1)
                            samples[i] *= (0.55 + 0.45*Math.sin(modulation*0.67));
                        if (modulationType==2)
                            omega += twopi*(frequency+1e3*Math.sin(modulation))/sampleRate;
                        else
                            omega += twopi*frequency/sampleRate;

                    }
                    audioTrack.write(samples, 0, bufferSize);
                }

                audioTrack.stop();
                audioTrack.release();
            }
        };

        // Start the audio thread
        thread.start();
    }

    private void stopAndDestroyThread(){
        if (isRunning) {
            isRunning = false;
            try {
                thread.join();
            } catch (InterruptedException | NullPointerException e) {
                e.printStackTrace();
            }
            thread = null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu); //your file name
        if(isPremium){
            if(menu.findItem(R.id.menu_promotion) != null)
                menu.findItem(R.id.menu_promotion).setVisible(false);
            if (menu.findItem(R.id.menu_why_ads) != null)
                menu.findItem(R.id.menu_why_ads).setVisible(false);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menu_credits:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage(R.string.icon_credit);
                builder.setPositiveButton(android.R.string.ok, null);
                builder.create().show();
                return true;
            case R.id.menu_share:
                String uri = "http://play.google.com/store/apps/details?id=" + getPackageName();
                Intent sharingIntent = new Intent(android.content.Intent.ACTION_SEND);
                sharingIntent.setType("text/plain");
                sharingIntent.putExtra(Intent.EXTRA_SUBJECT,getString(R.string.app_name));
                sharingIntent.putExtra(Intent.EXTRA_TEXT, uri);
                startActivity(Intent.createChooser(sharingIntent, getResources().getText(R.string.share)));
                return true;
            case R.id.menu_feedback:
                feedback();
                return true;
            case R.id.menu_why_ads:
                mBillingManager.initiatePurchaseFlow("premium", BillingClient.SkuType.INAPP);
                return true;
            case R.id.menu_promotion:
                promotion();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void feedback(){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getResources().getString(R.string.feedback_title));
        builder.setMessage(getResources().getString(R.string.feedback_msg));

        builder.setPositiveButton(getResources().getString(R.string.btn_rate_app), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                rateApp();
            }
        });

        builder.setNeutralButton(getResources().getString(R.string.btn_contact_us), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:dev.twinc@gmail.com?subject=DW%20feedback"));

                try {
                    startActivity(emailIntent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this,getResources().getString(R.string.txt_no_email),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton(getString(android.R.string.cancel), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.create().show();
    }

    @SuppressWarnings("deprecation")
    private void rateApp(){
        Uri uri = Uri.parse("market://details?id=" + getPackageName());
        Intent goToMarket = new Intent(Intent.ACTION_VIEW, uri);
        // To count with Play market backstack, After pressing back button,
        // to taken back to our application, we need to add following flags to intent.
        goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        else {
            //Suppress deprecation
            goToMarket.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        }

        try {
            startActivity(goToMarket);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=" + getPackageName())));
        }
    }

    public void onButtonAdsClick(@SuppressWarnings("UnusedParameters") View view){
        whyAds();
    }

    private void whyAds(){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.ads_title);
        builder.setMessage(R.string.ads_msg);

        builder.setPositiveButton(R.string.remove_ads, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                mBillingManager.initiatePurchaseFlow("premium", BillingClient.SkuType.INAPP);
            }
        });

        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

            }
        });

        builder.create().show();
    }

    private void promotion(){
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.premium_unlock);
        builder.setMessage(R.string.premium_msg);

        final EditText input = new EditText(getApplicationContext());
        input.setTextColor(getResources().getColor(android.R.color.black));
        input.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        builder.setView(input);

        builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (input.getText().toString().toLowerCase().trim().equals("twincapps")) {
                    // Activate premium
                    activatePremium();
                }
                else{
                    Toast.makeText(getApplicationContext(), R.string.invalid_code, Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNeutralButton(R.string.btn_contact_us, new DialogInterface.OnClickListener() {

            public void onClick(DialogInterface dialog, int which) {
                Intent emailIntent = new Intent(Intent.ACTION_SENDTO);
                emailIntent.setData(Uri.parse("mailto:dev.twinc@gmail.com?subject=Dog%20Whisperer%20premium"));
                try {
                    startActivity(emailIntent);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this,getResources().getString(R.string.txt_no_email),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.create().show();
    }

    private void activatePremium(){
        isPremium = true;
        SharedPreferences mainPrefs = getSharedPreferences(MAIN_PREFS, 0);
        SharedPreferences.Editor editor = mainPrefs.edit();
        editor.putBoolean("premium",isPremium);
        editor.apply();

        Toast.makeText(getApplicationContext(), R.string.welcome_premium, Toast.LENGTH_LONG).show();
        //Also now hide the ad and 'why ads?' button
        adView.setVisibility(View.GONE);
        adButton.setVisibility(View.GONE);
    }

    private class UpdateListener implements BillingManager.BillingUpdatesListener {
        @Override
        public void onPurchasesUpdated(List<Purchase> purchaseList) {
            for (Purchase purchase : purchaseList) {
                switch (purchase.getSku()) {
                    case "premium":
                        // Only toast if first time updating the premium purchase
                        if (!isPremium) {
                            activatePremium();
                        }
                        break;
                }
            }
        }
    }
}
