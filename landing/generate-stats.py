#!/usr/bin/env python3
"""
ReefBot Landing Stats Generator
Reads Java source files and outputs stats.json for the landing page.

Usage:
  python generate-stats.py

Run this after adding new buildings, resources, zones, or other game content.
Optionally add to a git pre-commit hook to keep stats always in sync.
"""

import re
import json
import sys
from pathlib import Path

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR  = Path(__file__).parent
SRC_ROOT    = SCRIPT_DIR.parent / "src" / "main" / "java" / "com" / "reefbot"
OUTPUT_FILE = SCRIPT_DIR / "stats.json"

# ── Helpers ────────────────────────────────────────────────────────────────────

def count_enum_values(file_path: Path) -> int:
    """Count enum constants in a Java enum file.
    Works for both simple enums (FOO, BAR) and complex ones with multi-line constructors.
    Strategy: find all 4-space-indented UPPER_CASE identifiers followed by ( , or ;
    """
    if not file_path.exists():
        print(f"  [WARN] Not found: {file_path}")
        return 0
    text = file_path.read_text(encoding="utf-8")
    # Remove block comments
    text = re.sub(r'/\*.*?\*/', '', text, flags=re.DOTALL)
    # Remove line comments
    text = re.sub(r'//[^\n]*', '', text)
    # Match lines like:    FOO( or    FOO, or    FOO;  (4-space indent, UPPER_CASE)
    matches = re.findall(r'^\s{4}([A-Z][A-Z0-9_]+)\s*[(\n,;]', text, re.MULTILINE)
    return len(matches)


def count_resource_fields(resource_type_path: Path) -> int:
    """Count resource types from ResourceType.java enum — the real source of truth."""
    return count_enum_values(resource_type_path)


def count_db_migrations(migration_dir: Path) -> int:
    """Count Flyway migration files."""
    if not migration_dir.exists():
        print(f"  [WARN] Migrations not found: {migration_dir}")
        return 0
    return len(list(migration_dir.glob("V*.sql")))


# ── Main ───────────────────────────────────────────────────────────────────────

def main():
    print("ReefBot Stats Generator")
    print("=" * 40)

    enums_dir  = SRC_ROOT / "enums"

    # Buildings
    building_type_file = enums_dir / "BuildingType.java"
    buildings = count_enum_values(building_type_file)
    print(f"  Buildings (BuildingType.java):  {buildings}")

    # Zones
    island_zone_file = enums_dir / "IslandZone.java"
    zones = count_enum_values(island_zone_file)
    print(f"  Zones (IslandZone.java):        {zones}")

    # Resources — count from ResourceType enum (source of truth)
    resource_type_file = enums_dir / "ResourceType.java"
    resources = count_resource_fields(resource_type_file)
    print(f"  Resources (ResourceType.java):  {resources}")

    # DB migrations — src/main/resources/db/migration
    migrations = SRC_ROOT.parent.parent.parent / "resources" / "db" / "migration"
    db_versions = count_db_migrations(migrations)
    print(f"  DB migrations (V*.sql):         {db_versions}")

    # Roadmap phases — static (update manually when adding a new phase)
    roadmap_phases = 4

    stats = {
        "buildings":      buildings      if buildings > 0  else 8,
        "resourceTypes":  resources      if resources > 0  else 4,
        "islandZones":    zones          if zones > 0      else 6,
        "roadmapPhases":  roadmap_phases,
        "dbMigrations":   db_versions    if db_versions > 0 else 5,
        "generatedAt":    __import__('datetime').datetime.utcnow().isoformat() + "Z"
    }

    OUTPUT_FILE.write_text(json.dumps(stats, indent=2, ensure_ascii=False), encoding="utf-8")

    print()
    print(f"Written → {OUTPUT_FILE}")
    print(json.dumps(stats, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
