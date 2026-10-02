/*
 * Copyright 2024-present Redouble AI, Inc. Authors
 *
 * Licensed under the Apache-2.0 license (https://www.apache.org/licenses/LICENSE-2.0).
 */

package ai.redouble.demo.pricing;

import java.time.*;
import java.time.format.*;
import java.util.*;

/**
 * The logic of "which price is current", with no model in it. Every mention from every
 * document is placed under its product (the canonicalizer's groups say which names are
 * one product), then per audience and currency the prices the documents present as in
 * force compete by date, as of the day the run answers for: the latest one dated on or
 * before that day is current, one dated after it is scheduled, every other statement of
 * the same amount confirms the current one, the earlier ones with another amount are
 * superseded by it, two that disagree on the same latest date are a conflict for a person,
 * and one stated by a document with no date cannot be ranked and says so. What a document itself
 * calls a former price, a proposal, or a cost never competes; a discontinuation dates the
 * product's end. A point's date is the price's own effective date, else the statement's
 * date, else the document's.
 *
 * @author Andrey Santrosyan
 * @since 0.1 (2026-09-15)
 */
public final class PriceHistory {
    /** One mention with where it came from and the date its document carries. */
    public record Sourced(String path, String documentDate, PriceMention mention) {}

    /** What the reconciliation produced: the products, and the mentions it could not use. */
    public record Result(List<ProductPricing> products, List<PricePoint> ignored) {}

    private PriceHistory() {}

    /**
     * @param asOf the day the run answers for: a price dated after it is scheduled, not current
     */
    public static Result reconcile(List<Sourced> mentions, ProductGroups groups, LocalDate asOf) {
        Map<String, String> canonicalByAlias = new HashMap<>();
        if (groups != null && groups.getGroups() != null) {
            for (ProductGroups.Group group : groups.getGroups()) {
                if (group.getCanonical() == null || group.getAliases() == null) {
                    continue;
                }
                for (String alias : group.getAliases()) {
                    canonicalByAlias.put(key(alias), group.getCanonical().trim());
                }
                canonicalByAlias.put(key(group.getCanonical()), group.getCanonical().trim());
            }
        }
        Map<String, ProductPricing> products = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<PricePoint> ignored = new ArrayList<>();
        // a change names no currency of its own when the document does not; it joins the series it applies to once every price is placed
        Map<ProductPricing, List<PricePoint>> changes = new LinkedHashMap<>();
        for (Sourced sourced : mentions) {
            PriceMention m = sourced.mention();
            PricePoint point = point(sourced);
            if (m.getProduct() == null || m.getProduct().isBlank()) {
                ignore(ignored, point, "no product named");
                continue;
            }
            if (m.getKind() == null) {
                ignore(ignored, point, "no kind");
                continue;
            }
            String canonical = canonicalByAlias.getOrDefault(key(m.getProduct()), m.getProduct().trim());
            ProductPricing product = products.computeIfAbsent(canonical, name -> {
                ProductPricing p = new ProductPricing();
                p.setProduct(name);
                return p;
            });
            if (!product.getAliases().contains(m.getProduct().trim())) {
                product.getAliases().add(m.getProduct().trim());
            }
            switch (m.getKind()) {
                case COST -> {
                    point.setStatus(PricePoint.Status.COST);
                    product.getCosts().add(point);
                }
                case DISCONTINUATION -> {
                    point.setStatus(PricePoint.Status.DISCONTINUED);
                    if (product.getDiscontinuedFrom() == null || (point.getDate() != null && point.getDate().compareTo(product.getDiscontinuedFrom()) > 0)) {
                        product.setDiscontinuedFrom(point.getDate());
                        product.setDiscontinuation(point);
                    }
                }
                case CHANGE -> {
                    if (m.getPercentChange() == null) {
                        ignore(ignored, point, "a change with no percentage");
                    }
                    else {
                        changes.computeIfAbsent(product, p -> new ArrayList<>()).add(point);
                    }
                }
                default -> {
                    if (m.getAmount() == null) {
                        ignore(ignored, point, "no amount");
                    }
                    else if (m.getCurrency() == null || m.getCurrency().isBlank()) {
                        ignore(ignored, point, "no currency");
                    }
                    else {
                        series(product, point.getAudience(), point.getCurrency()).getPoints().add(point);
                    }
                }
            }
        }
        for (Map.Entry<ProductPricing, List<PricePoint>> pending : changes.entrySet()) {
            for (PricePoint change : pending.getValue()) {
                PriceSeries target = seriesFor(pending.getKey(), change);
                if (target != null) {
                    target.getPoints().add(change);
                }
                else {
                    ignore(ignored, change, change.getCurrency() == null
                            ? "a change with no currency and no single " + change.getAudience() + " series of " + pending.getKey().getProduct() + " to apply it to"
                            : "a change with no " + change.getAudience() + " " + change.getCurrency() + " price of " + pending.getKey().getProduct() + " to apply it to");
                }
            }
        }
        for (ProductPricing product : products.values()) {
            for (PriceSeries series : product.getSeries()) {
                rank(series, product.getDiscontinuedFrom(), asOf.toString());
            }
            product.getSeries().sort(Comparator.comparing(PriceSeries::getAudience).thenComparing(PriceSeries::getCurrency));
            product.getCosts().sort(byDateDesc());
        }
        return new Result(new ArrayList<>(products.values()), ignored);
    }

    /** The prices in force compete by date; everything else is labelled for what it is. */
    private static void rank(PriceSeries series, String discontinuedFrom, String asOf) {
        List<PricePoint> inForce = new ArrayList<>();
        List<PricePoint> changes = new ArrayList<>();
        for (PricePoint point : series.getPoints()) {
            switch (point.getKind()) {
                case LIST, TRANSACTION -> inForce.add(point);
                case CHANGE -> changes.add(point);
                case FORMER -> point.setStatus(PricePoint.Status.FORMER);
                case PROPOSED -> point.setStatus(PricePoint.Status.PROPOSED);
                default -> throw new IllegalStateException("A " + point.getKind() + " mention never enters a series");
            }
        }
        apply(changes, inForce);
        List<PricePoint> dated = new ArrayList<>();
        boolean scheduled = false;
        for (PricePoint point : inForce) {
            if (point.getDate() == null) {
                point.setStatus(PricePoint.Status.UNDATED);
            }
            else if (point.getDate().compareTo(asOf) > 0) {
                // decided, and in force from a day after the one this run answers for
                point.setStatus(PricePoint.Status.SCHEDULED);
                point.setNote(join(point.getNote(), "in force from " + point.getDate() + ", after " + asOf));
                scheduled = true;
            }
            else {
                dated.add(point);
            }
        }
        if (dated.isEmpty()) {
            series.setStatus(scheduled ? PriceSeries.Status.SCHEDULED
                    : inForce.isEmpty() ? PriceSeries.Status.NOT_IN_FORCE : PriceSeries.Status.UNDATED);
            for (PricePoint point : inForce) {
                if (point.getStatus() == PricePoint.Status.UNDATED) {
                    point.setNote(join(point.getNote(), "stated as in force by a document with no date, so it cannot be ranked"));
                }
            }
        }
        else {
            dated.sort(byDateDesc());
            String top = dated.get(0).getDate();
            Set<Double> amountsOnTop = new TreeSet<>();
            for (PricePoint point : dated) {
                if (point.getDate().equals(top)) {
                    amountsOnTop.add(point.getAmount());
                }
            }
            boolean conflict = amountsOnTop.size() > 1;
            Double currentAmount = amountsOnTop.iterator().next();
            series.setStatus(conflict ? PriceSeries.Status.CONFLICT : PriceSeries.Status.CURRENT);
            for (PricePoint point : dated) {
                if (conflict && point.getDate().equals(top)) {
                    point.setStatus(PricePoint.Status.CONFLICT);
                    point.setNote(join(point.getNote(), "the documents state " + amountsOnTop + " " + series.getCurrency() + " for the same date " + top + "; a person decides"));
                }
                else if (!conflict && point.getAmount().equals(currentAmount)) {
                    // one point is the current price; every other statement of the same amount confirms it
                    if (series.getCurrent() == null) {
                        point.setStatus(PricePoint.Status.CURRENT);
                        series.setCurrent(point);
                    }
                    else {
                        point.setStatus(PricePoint.Status.CONFIRMED);
                        point.setNote(join(point.getNote(), "the same amount as the current price, stated " + (point.getDate().equals(top) ? "on the same date" : "earlier, on " + point.getDate())));
                    }
                }
                else {
                    point.setStatus(PricePoint.Status.SUPERSEDED);
                    point.setNote(join(point.getNote(), conflict
                            ? "later statements on " + top + " disagree: " + amountsOnTop + " " + series.getCurrency()
                            : "replaced from " + top + " by " + currentAmount + " " + series.getCurrency()));
                }
            }
            for (PricePoint point : inForce) {
                if (point.getStatus() == PricePoint.Status.UNDATED) {
                    point.setNote(join(point.getNote(), "stated without a date, so it cannot be ranked against the dated prices"));
                }
            }
            if (discontinuedFrom != null && series.getCurrent() != null) {
                series.getCurrent().setNote(join(series.getCurrent().getNote(), "the product is discontinued from " + discontinuedFrom + "; this is its last price"));
            }
        }
        series.getPoints().sort(Comparator.comparingInt(PriceHistory::order).thenComparing(byDateDesc()));
    }

    /**
     * A decided change becomes a price: its percentage applied to the latest price in force
     * before its date, earlier changes first so a change can build on a change. It then
     * competes as a price in force from its date. A change with no date, no percentage, or
     * no single price before it to apply to is left unapplied and says why.
     */
    private static void apply(List<PricePoint> changes, List<PricePoint> inForce) {
        changes.sort(Comparator.comparing(PricePoint::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
        for (PricePoint change : changes) {
            change.setStatus(PricePoint.Status.UNAPPLIED);
            if (change.getDate() == null) {
                change.setNote(join(change.getNote(), "a change with no date cannot be applied to anything"));
                continue;
            }
            PricePoint base = null;
            boolean disagree = false;
            for (PricePoint point : inForce) {
                if (point.getDate() == null || point.getDate().compareTo(change.getDate()) >= 0) {
                    continue;
                }
                if (base == null || point.getDate().compareTo(base.getDate()) > 0) {
                    base = point;
                    disagree = false;
                }
                else if (point.getDate().equals(base.getDate()) && !point.getAmount().equals(base.getAmount())) {
                    disagree = true;
                }
            }
            if (base == null) {
                change.setNote(join(change.getNote(), "no price in force before " + change.getDate() + " to apply " + change.getPercentChange() + "% to"));
                continue;
            }
            if (disagree) {
                change.setNote(join(change.getNote(), "the prices in force before " + change.getDate() + " disagree, so " + change.getPercentChange() + "% has nothing single to apply to"));
                continue;
            }
            change.setAmount(Math.round(base.getAmount() * (1 + change.getPercentChange() / 100) * 100) / 100.0);
            change.setAppliedTo(base.getAmount() + " from " + base.getSource() + " (" + base.getDate() + ")");
            change.setNote(join(change.getNote(), change.getPercentChange() + "% applied to " + change.getAppliedTo()));
            change.setStatus(null);
            inForce.add(change);
        }
    }

    /** The series a change applies to: its audience, and its currency when it names one, else the audience's only series. */
    private static PriceSeries seriesFor(ProductPricing product, PricePoint change) {
        PriceSeries match = null;
        for (PriceSeries series : product.getSeries()) {
            if (series.getAudience() != change.getAudience()) {
                continue;
            }
            if (change.getCurrency() != null) {
                if (series.getCurrency().equals(change.getCurrency())) {
                    return series;
                }
            }
            else if (match == null) {
                match = series;
            }
            else {
                return null;
            }
        }
        if (match != null) {
            change.setCurrency(match.getCurrency());
        }
        return match;
    }

    private static int order(PricePoint point) {
        return switch (point.getStatus()) {
            case SCHEDULED, CURRENT, CONFLICT -> 0;
            case CONFIRMED -> 1;
            case SUPERSEDED -> 2;
            case UNDATED -> 3;
            case FORMER -> 4;
            case PROPOSED -> 5;
            case UNAPPLIED -> 6;
            default -> 7;
        };
    }

    private static Comparator<PricePoint> byDateDesc() {
        return Comparator.comparing(PricePoint::getDate, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private static PriceSeries series(ProductPricing product, PriceMention.Audience audience, String currency) {
        for (PriceSeries series : product.getSeries()) {
            if (series.getAudience() == audience && series.getCurrency().equals(currency)) {
                return series;
            }
        }
        PriceSeries series = new PriceSeries();
        series.setAudience(audience);
        series.setCurrency(currency);
        product.getSeries().add(series);
        return series;
    }

    /** The mention as a point: its date settled, its fields carried over. */
    static PricePoint point(Sourced sourced) {
        PriceMention m = sourced.mention();
        PricePoint point = new PricePoint();
        point.setAmount(m.getAmount());
        point.setPercentChange(m.getPercentChange());
        point.setCurrency(m.getCurrency() != null && !m.getCurrency().isBlank() ? m.getCurrency().trim().toUpperCase() : null);
        point.setVariant(m.getVariant());
        point.setAudience(m.getAudience() != null ? m.getAudience() : PriceMention.Audience.UNSPECIFIED);
        point.setKind(m.getKind());
        point.setSource(sourced.path());
        point.setQuote(m.getQuote());
        point.setDateBasis(m.getDateBasis());
        String date = isoDate(m.getEffectiveFrom(), point);
        if (date == null) {
            date = isoDate(m.getStatedOn(), point);
        }
        if (date == null && sourced.documentDate() != null) {
            date = isoDate(sourced.documentDate(), point);
            if (date != null) {
                point.setDateBasis(join(point.getDateBasis(), "dated by the document's own date " + date));
            }
        }
        point.setDate(date);
        return point;
    }

    /** The date as the model wrote it, when it is one; a string that is not a date is noted and treated as none. */
    private static String isoDate(String text, PricePoint point) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.trim()).toString();
        }
        catch (DateTimeParseException e) {
            point.setNote(join(point.getNote(), "'" + text + "' is not an ISO date and was not used"));
            return null;
        }
    }

    private static void ignore(List<PricePoint> ignored, PricePoint point, String reason) {
        point.setStatus(PricePoint.Status.IGNORED);
        point.setNote(join(point.getNote(), reason));
        ignored.add(point);
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String join(String a, String b) {
        return a == null || a.isEmpty() ? b : a + "; " + b;
    }
}
