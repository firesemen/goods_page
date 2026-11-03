package ru.fridgedays.app;

import java.util.Map;
import ru.fridgedays.core.Invitation;

/** The data boundary of one independently identified synchronization participant. */
interface SyncReplica {
    String actorId();
    Invitation invitation();
    Map<String,String> packets();
    void merge(Map<String,String> incoming)throws Exception;
}
