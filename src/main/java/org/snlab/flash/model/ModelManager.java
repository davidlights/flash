package org.snlab.flash.model;


import java.util.*;

import org.snlab.network.Device;
import org.snlab.network.Network;
import org.snlab.network.Port;
import org.snlab.network.Rule;

/**
 * An instance of Verifier maintains (1) all verified rules and (2) network model,
 * while the per-vertex model is not maintained.
 */
public class ModelManager {
    public final BDDEngine bddEngine;
    private int size = 32;
    private final HashMap<Device, TrieRules> deviceToRules;

    public HashMap<Ports, Integer> portsToPredicate;

    private double s1 = 0, s1to2 = 0, s2 = 0, sports = 0;

    public ModelManager(Network network) {
        this(network, new BDDEngine(32), new PersistentPorts());
    }

    public ModelManager(Network network, int size) {
        this(network, new BDDEngine(size), new PersistentPorts());
        this.size = size;
    }

    public ModelManager(Network network, Ports base) {
        this(network, new BDDEngine(32), base);
    }

    public ModelManager(Network network, int size, Ports base) {
        this(network, new BDDEngine(size), base);
        this.size = size;
    }

    public ModelManager(Network network, BDDEngine bddEngine, Ports base) {
        this.bddEngine = bddEngine;
        this.deviceToRules = new HashMap<>();

        // Relabel every device as the index used by Ports, starting from 0
        for (Device device : network.getAllDevices()) this.deviceToRules.put(device, new TrieRules());

        // Each device has a default rule with default action.
        ArrayList<Port> key = new ArrayList<>();
        for (Device device : network.getAllDevices()) {
            Port p = device.getPort("default");
            key.add(p);
            Rule rule = new Rule(device, 0, 0, -1, p);
            rule.setBddmatch(BDDEngine.BDDTrue);
            deviceToRules.get(device).insert(rule, size);
        }


        // The only one EC takes default actions.
        this.portsToPredicate = new HashMap<>();
        this.portsToPredicate.put(base.create(key, 0, key.size()), BDDEngine.BDDTrue);
    }

    public Changes insertMiniBatch(List<Rule> insertions) {
        return this.miniBatch(insertions, new ArrayList<>());
    }

    /**
     * Notates current data-plane (flow rules) as f, consider transition to f'
     * @param insertions f' - f
     * @param deletions  f - f'
     * @return the change \chi
     */
    public Changes miniBatch(List<Rule> insertions, List<Rule> deletions) {
        s1 -= System.nanoTime();
        HashSet<Rule> deleted = new HashSet<>();
        HashSet<Rule> inserted = new HashSet<>();
        for (Rule rule : deletions) {
            deleted.add(rule);
            deviceToRules.get(rule.getDevice()).remove(rule, size);
        }
        for (Rule rule : insertions) {
            if (deleted.contains(rule)) {
                deleted.remove(rule);
                continue;
            }
            inserted.add(rule);
            rule.setBddmatch(bddEngine.encodeIpv4(rule.getMatch(), rule.getPrefix(), rule.getSrc(), rule.getSrcPrefix()));
            deviceToRules.get(rule.getDevice()).insert(rule, size);
        }
        Changes ret = new Changes(bddEngine);
        for (Rule rule : deleted) identifyChangesDeletion(rule, ret);
        for (Rule rule : inserted) identifyChangesInsert(rule, ret);
        s1 += System.nanoTime();
        return ret;
    }
    /**
     * @param rule an inserted rule
     * @param ret  the pointer to the value returned by this function
     */
    private void identifyChangesInsert(Rule rule, Changes ret) {
        rule.setHit(bddEngine.ref(rule.getBddmatch()));

        for (Rule r : deviceToRules.get(rule.getDevice()).getAllOverlappingWith(rule, size)) {
            if (r.getPriority() > rule.getPriority()) {
                int newHit = bddEngine.diff(rule.getHit(), r.getBddmatch());
                bddEngine.deRef(rule.getHit());
                rule.setHit(newHit);

                if (rule.getHit() == BDDEngine.BDDFalse) break;
            }
        }

        if (rule.getHit() != BDDEngine.BDDFalse) {
            s1 += System.nanoTime();
            s1to2 -= System.nanoTime();
            ret.add(rule.getHit(), null, rule.getOutPort());
            s1to2 += System.nanoTime();
            s1 -= System.nanoTime();
        }
    }

    private void identifyChangesDeletion(Rule rule, Changes ret) {
        TrieRules targetNode = deviceToRules.get(rule.getDevice());
        rule.setHit(bddEngine.ref(rule.getBddmatch()));

        ArrayList<Rule> sorted = targetNode.getAllOverlappingWith(rule, size);
        Comparator<Rule> comp = (Rule lhs, Rule rhs) -> rhs.getPriority() - lhs.getPriority();
        sorted.sort(comp);

        for (Rule r : sorted) {
            if (r.getPriority() > rule.getPriority()) {
                int newHit = bddEngine.diff(rule.getHit(), r.getBddmatch());
                bddEngine.deRef(rule.getHit());
                rule.setHit(newHit);
            }

            if (rule.getHit() == BDDEngine.BDDFalse) break;

            if (r.getPriority() < rule.getPriority()) {
                int intersection = bddEngine.and(r.getBddmatch(), rule.getHit());

                int newHit = bddEngine.diff(rule.getHit(), intersection);
                bddEngine.deRef(rule.getHit());
                rule.setHit(newHit);

                if (intersection != BDDEngine.BDDFalse && r.getOutPort() != rule.getOutPort()) {
                    s1 += System.nanoTime();
                    s1to2 -= System.nanoTime();
                    ret.add(intersection, rule.getOutPort(), r.getOutPort());
                    s1to2 += System.nanoTime();
                    s1 -= System.nanoTime();
                } else {
                    bddEngine.deRef(intersection);
                }
            }
        }
        bddEngine.deRef(rule.getBddmatch());
        bddEngine.deRef(rule.getHit());
    }


    private void insertPredicate(HashMap<Ports, Integer> newPortsToPreds, Ports newPorts, Integer predicate) {
        if (newPortsToPreds.containsKey(newPorts)) {
            int t = newPortsToPreds.get(newPorts);
            newPortsToPreds.replace(newPorts, bddEngine.or(t, predicate));
            bddEngine.deRef(predicate);
            bddEngine.deRef(t);
        } else {
            newPortsToPreds.put(newPorts, predicate);
        }
    }

    /**
     * Updates the PPM following changes and returns all transferred ECs.
     *
     * @param changes -
     * @return -
     */
    public HashSet<Integer> update(Changes changes) {
        s1to2 -= System.nanoTime();
        changes.aggrBDDs();
        s1to2 += System.nanoTime();


        s2 -= System.nanoTime();
        HashSet<Integer> transferredECs = new HashSet<>();

        for (Map.Entry<Integer, TreeMap<Integer, Port>> entryI : changes.getAll().entrySet()) {
            int delta = entryI.getKey();
            bddEngine.ref(delta);

            HashMap<Ports, Integer> newPortsToPreds = new HashMap<>();
            for (Map.Entry<Ports, Integer> entry : portsToPredicate.entrySet()) {
                Ports ports = entry.getKey();
                Integer predicate = entry.getValue();
                if (delta == BDDEngine.BDDFalse) { // change already becomes empty
                    insertPredicate(newPortsToPreds, ports, predicate);
                    continue;
                }

                int intersection = bddEngine.and(predicate, delta);
                if (intersection == BDDEngine.BDDFalse) { // EC is not affected by change
                    insertPredicate(newPortsToPreds, ports, predicate);
                    bddEngine.deRef(intersection);
                    continue;
                } else {
                    // clean up the intermediate variables
                    int t = bddEngine.diff(delta, intersection);
                    bddEngine.deRef(delta);
                    delta = t;
                }


                if (intersection != predicate) {
                    // EC is partially affected by change, which causes split
                    transferredECs.add(intersection);
                    insertPredicate(newPortsToPreds, ports, bddEngine.diff(predicate, intersection));
                }
                // The intersection is transferred
                sports -= System.nanoTime();
                Ports portsT = ports.createWithChanges(entryI.getValue());
                sports += System.nanoTime();
                insertPredicate(newPortsToPreds, portsT, intersection);
                bddEngine.deRef(predicate);
            }

            bddEngine.deRef(delta);
            portsToPredicate = newPortsToPreds;
        }
        s2 += System.nanoTime();

        // Manually deref BDDs used by Changes since its deconstructor doesn't handle this.
        changes.releaseBDDs();
        return transferredECs;
    }

    public HashMap<Port, HashSet<Integer>> getPortToPredicate() {
        HashMap<Port, HashSet<Integer>> ret = new HashMap<>();
        for (Map.Entry<Ports, Integer> entry : portsToPredicate.entrySet())
            for (Port p : entry.getKey().getAll()) {
                ret.putIfAbsent(p, new HashSet<>());
                ret.get(p).add(entry.getValue());
            }
        return ret;
    }

    public int predSize() {
        return this.portsToPredicate.size();
    }

    public double printTime(int size) {
        if (size == 0) {
            long nsToMs = 1000L * 1000L;
            System.out.println("    Stage 1 (Change Computation) " + (s1 / nsToMs) + " ms in total");
            System.out.println("    Composition of changes " + (s1to2 / nsToMs) + " ms in total");
            System.out.println("    Stage 2 (EC Update) " + (s2 / nsToMs) + " ms in total");
            System.out.println("    Ports " + (sports / nsToMs) + " ms in total");
        } else {
            long nsToUsPU = 1000L * size;
            System.out.println("    Stage 1 (Change Computation) " + (s1 / nsToUsPU) + " us per-update");
            System.out.println("    Composition of changes " + (s1to2 / nsToUsPU) + " us per-update");
            System.out.println("    Stage 2 (EC Update) " + (s2 / nsToUsPU) + " us per-update");
            System.out.println("    Ports " + (sports / nsToUsPU) + " us per-update");
        }
        return s1 + s1to2 + s2;
    }
}