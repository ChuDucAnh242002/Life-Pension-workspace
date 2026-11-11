package fi.adviser.workspace;

import static fi.adviser.workspace.BriefingModels.*;

public interface BriefingModel {
    boolean enabled();
    String provider();
    String model();
    Selection select(Context context);
}
