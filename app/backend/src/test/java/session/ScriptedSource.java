package session;

import model.Prices;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A reference source a test scripts: what it answers, how many times it was read, and on which threads. */
public final class ScriptedSource implements ReferenceSource {

    public static final SessionReference ASML = new SessionReference("ASML", Prices.TICK, 1L, Prices.SCALE);

    public enum Mode { NORMAL, UNAVAILABLE, UNUSABLE }

    public volatile Mode mode = Mode.NORMAL;
    public volatile SessionReference reference = ASML;
    public final Set<Integer> closedDates = Collections.synchronizedSet(new HashSet<>());
    public final List<String> loadThreads = Collections.synchronizedList(new ArrayList<>());
    public volatile Runnable duringLoad = () -> { };

    public int loads() {
        return loadThreads.size();
    }

    @Override
    public SessionDay load(int businessDate) {
        loadThreads.add(Thread.currentThread().getName());
        duringLoad.run();
        switch (mode) {
            case UNAVAILABLE:
                throw new ReferenceUnavailableException("scripted: database down", null);
            case UNUSABLE:
                throw new ReferenceDataException("scripted: no instrument row");
            default:
                return new SessionDay(reference, !closedDates.contains(businessDate));
        }
    }

    @Override
    public String remedy() {
        return "scripted remedy";
    }
}
