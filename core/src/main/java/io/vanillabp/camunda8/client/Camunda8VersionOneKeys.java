package io.vanillabp.camunda8.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Finds the keys of the Camunda 8 adapter of VanillaBP 1 which this version does not read, and
 * says in one WARN per key what to write instead.
 * <p>
 * Both platforms bind the configuration so that an unknown key is ignored. A key of version 1
 * which this version does not read would then change nothing, and nobody would be told. The
 * start goes on, because each of these keys only tunes something which works without it. The
 * keys version 1 had, and what became of each of them, are in the repository's UPGRADE.md.
 * <p>
 * The keys are read from the property names the platform knows, in the dotted form of a
 * configuration file. Case and dashes do not matter, so <code>taskTimeout</code> is found as
 * well as <code>task-timeout</code>. An environment variable spells a key in a form which
 * cannot be taken apart again without guessing, so it is not looked at.
 */
public final class Camunda8VersionOneKeys {

  private Camunda8VersionOneKeys() {
  }

  /**
   * A key below <code>adapters.&lt;id&gt;</code> of a scope level which version 1 read and
   * this version does not.
   *
   * @param name The key as version 1 spelled it
   * @param successor What to write instead. <code>%1$s</code> is the path of the scope
   *          level the key was found at, ending in <code>adapters.&lt;id&gt;</code>.
   *          <code>%2$s</code> is the adapter id
   */
  private record ScopedKey(
                           String name,
                           String successor) {
  }

  private static final List<ScopedKey> SCOPED_KEYS = List
      .of(
          new ScopedKey(
              "task-timeout", "It was renamed. Write '%1$s.job-timeout' instead. It is read at the same levels."),
          new ScopedKey(
              "poll-interval", "It is read at adapter level only now. Write 'vanillabp.adapters.%2$s.poll-interval' "
                  + "instead."),
          new ScopedKey(
              "poll-request-timeout", "It was renamed and is read at adapter level only now. Write "
                  + "'vanillabp.adapters.%2$s.request-timeout' instead."),
          new ScopedKey(
              "stream-enabled", "It is read at adapter level only now. Write 'vanillabp.adapters.%2$s.stream-enabled' "
                  + "instead."),
          new ScopedKey(
              "stream-timeout", "It is read at adapter level only now. Write 'vanillabp.adapters.%2$s.stream-timeout' "
                  + "instead."),
          new ScopedKey(
              "use-tenants", "It is gone. 'use-tenants: true' is what this version does by default, so just remove "
                  + "the key. For 'use-tenants: false' write 'vanillabp.adapters.%2$s.name-clash-avoidance: "
                  + "none' instead."),
          new ScopedKey(
              "task-id-as-hex-string", "It is gone, and there is nothing to write instead: a task id is always decimal now. "
                  + "Task ids your application stored in hexadecimal are data to migrate, see "
                  + "UPGRADE.md of the Camunda 8 adapter."));

  /**
   * Reports every key of version 1 which this version does not read, for the Camunda 8 adapter
   * ids of this application.
   *
   * @param adapterIds The ids of the configured adapters of type <code>camunda8</code>
   * @param propertyNames Every property name the platform knows
   * @param warnLogger Where the warnings go. The start goes on
   */
  public static void reportKeysOfVersionOne(
      final Collection<String> adapterIds,
      final Iterable<String> propertyNames,
      final Consumer<String> warnLogger) {

    if (adapterIds.isEmpty()) {
      return;
    }
    final var reported = new ArrayList<String>();
    for (final var name : propertyNames) {
      if ((name == null) || reported.contains(name)) {
        continue;
      }
      final var warning = warningFor(adapterIds, name);
      if (warning != null) {
        reported.add(name);
        warnLogger.accept(warning);
      }
    }

  }

  /**
   * The warning about one property name, or <code>null</code> where the name is not a key of
   * version 1 which this version leaves unread.
   *
   * @param adapterIds The ids of the configured adapters of type <code>camunda8</code>
   * @param name One property name
   * @return The warning or <code>null</code>
   */
  static String warningFor(
      final Collection<String> adapterIds,
      final String name) {

    final var parts = name.split("\\.");
    if ((parts.length < 2) || !is(parts[0], "vanillabp")) {
      return null;
    }
    // version 1 read allow-connectors outside of any adapter section
    if (is(parts[parts.length - 1], "allow-connectors")) {
      final var location = allowConnectorsLocation(parts);
      if (location != null) {
        return ("Camunda 8 adapter: '%s' is a key of VanillaBP 1 which this version does not read. It "
            + "moved into the adapter section. Write '%s' instead, once for each Camunda 8 adapter id "
            + "(%s).").formatted(name, location, String.join(", ", adapterIds));
      }
      return null;
    }
    final var adapterSection = adapterSectionLength(parts);
    if ((adapterSection < 0) || (parts.length != adapterSection + 1)) {
      return null;
    }
    final var adapterId = adapterIds
        .stream()
        .filter(id -> is(parts[adapterSection - 1], id))
        .findFirst();
    if (adapterId.isEmpty()) {
      return null;
    }
    final var key = parts[adapterSection];
    final var path = String.join(".", Arrays.copyOf(parts, adapterSection));
    return SCOPED_KEYS
        .stream()
        .filter(scopedKey -> is(key, scopedKey.name()))
        .findFirst()
        .map(scopedKey -> "Camunda 8 adapter '%s': '%s' is a key of VanillaBP 1 which this version does not read. %s"
            .formatted(adapterId.get(), name, scopedKey.successor().formatted(path, adapterId.get())))
        .orElse(null);

  }

  /**
   * Where <code>allow-connectors</code> belongs now, for a key at one of the three places
   * version 1 read it, or <code>null</code> for any other place.
   */
  private static String allowConnectorsLocation(
      final String[] parts) {

    if (parts.length == 2) {
      return "vanillabp.adapters.<id>.allow-connectors";
    }
    if ((parts.length == 4) && is(parts[1], "workflow-modules")) {
      return "vanillabp.workflow-modules.%s.adapters.<id>.allow-connectors".formatted(parts[2]);
    }
    if ((parts.length == 6) && is(parts[1], "workflow-modules") && is(parts[3], "workflows")) {
      return "vanillabp.workflow-modules.%s.workflows.%s.adapters.<id>.allow-connectors"
          .formatted(parts[2], parts[4]);
    }
    return null;

  }

  /**
   * The number of parts up to and including the adapter id of a section
   * <code>adapters.&lt;id&gt;</code> below a workflow module, a workflow or a task. It is
   * <code>-1</code> where the name has no such section.
   */
  private static int adapterSectionLength(
      final String[] parts) {

    if ((parts.length < 5) || !is(parts[1], "workflow-modules")) {
      return -1;
    }
    if (is(parts[3], "adapters")) {
      return 5;
    }
    if ((parts.length >= 7) && is(parts[3], "workflows") && is(parts[5], "adapters")) {
      return 7;
    }
    if ((parts.length >= 9) && is(parts[3], "workflows") && is(parts[5], "tasks") && is(parts[7], "adapters")) {
      return 9;
    }
    return -1;

  }

  /**
   * Whether a part of a property name is the given word, the way Spring Boot compares them:
   * without case, dashes and underscores.
   */
  private static boolean is(
      final String part,
      final String word) {

    return normalized(part).equals(normalized(word));

  }

  private static String normalized(
      final String text) {

    return text
        .replace("-", "")
        .replace("_", "")
        .toLowerCase(Locale.ROOT);

  }

}
