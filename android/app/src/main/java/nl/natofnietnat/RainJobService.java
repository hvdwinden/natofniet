package nl.natofnietnat;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Wordt elke ~15 minuten door Android gestart om de regenverwachting te controleren. */
public class RainJobService extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try { RainCheck.run(getApplicationContext()); }
            finally { jobFinished(params, false); }
        }).start();
        return true;   // werk loopt nog op de achtergrond
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;   // opnieuw proberen als Android de taak afbreekt
    }
}
