package cc.useforce.mc.core;

import java.util.List;

/** Which session the player has picked: the UI's own state, never Force's (SPEC §54 principle 3). */
public final class Selection {
    private volatile String address;

    public String address() { return address; }

    public void set(String address) { this.address = address; }

    /** The selected session if it is still live, else the best one to show (see {@link #attention}). */
    public String resolve(ForceModel.State s) {
        String a = address;
        if (a != null) {
            ForceModel.Session cur = s.find(a);
            if (cur != null && cur.live()) return a;
        }
        String pick = attention(s.live(), null);
        if (pick == null && !s.live().isEmpty()) pick = s.live().get(0).address();
        address = pick;
        return pick;
    }

    /** `delta` steps through the live list from the selected one, wrapping. */
    public String step(ForceModel.State s, int delta) {
        List<ForceModel.Session> live = s.live();
        if (live.isEmpty()) return address;
        int i = indexOf(live, address);
        int n = live.size();
        int next = i < 0 ? (delta > 0 ? 0 : n - 1) : Math.floorMod(i + delta, n);
        address = live.get(next).address();
        return address;
    }

    /** The first session that needs you, then unread, then your turn, after `from` (wrapping), or null. */
    public static String attention(List<ForceModel.Session> live, String from) {
        int start = indexOf(live, from) + 1;
        for (String want : new String[]{"needs-you", "unread", "your-turn"}) {
            for (int k = 0; k < live.size(); k++) {
                ForceModel.Session s = live.get((start + k) % live.size());
                boolean hit = want.equals("unread") ? s.unread() : s.state().equals(want);
                if (hit) return s.address();
            }
        }
        return null;
    }

    private static int indexOf(List<ForceModel.Session> live, String address) {
        if (address == null) return -1;
        for (int i = 0; i < live.size(); i++) if (live.get(i).address().equals(address)) return i;
        return -1;
    }
}
