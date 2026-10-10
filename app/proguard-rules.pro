# Connectors are found through ConnectorRegistry (no reflection), so the default rules are enough.
# org.json is part of the platform.
-dontwarn org.json.**
# commons-suncalc (sunrise and sunset) marks its API with SpotBugs annotations: a compile-time-only dependency that is
# not shipped, and annotations only, so nothing is missing at run time.
-dontwarn edu.umd.cs.findbugs.annotations.**
