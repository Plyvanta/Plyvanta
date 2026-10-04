package app.plyvanta;

import android.app.Application;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;

import java.util.Locale;

import app.plyvanta.extractor.OkHttpDownloader;
import app.plyvanta.network.AppNetwork;
import app.plyvanta.update.UpdateNotificationManager;
import app.plyvanta.update.UpdateScheduler;

public final class PlyvantaApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        AppNetwork.initialize(this);

        Locale locale = Locale.getDefault();
        String country = locale.getCountry();
        Localization localization = new Localization(locale.getLanguage(), country);
        ContentCountry contentCountry = country.isEmpty()
                ? ContentCountry.DEFAULT
                : new ContentCountry(country);
        NewPipe.init(new OkHttpDownloader(), localization, contentCountry);

        UpdateNotificationManager.createChannel(this);
        UpdateScheduler.schedule(this);
    }
}
