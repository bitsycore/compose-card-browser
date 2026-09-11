#!/usr/bin/env python3
"""
Rebuilds `providers/wuwa/src/commonMain/composeResources/files/wuwa-cards.json` from UCP's card endpoints.

Run from the repository root:

    python providers/wuwa/tools/scrape_wuwa.py

The catalogue is 130-odd printings and the whole game fits in one file, which is why the adapter
serves a bundled snapshot rather than calling an undocumented endpoint on every launch. This script
is what makes that snapshot reproducible: the provenance block it writes records the day it ran and
what each locale answered, so a stale file is visible rather than merely suspected.

## What "aligning" means here

Each locale is a separate catalogue with its own numeric card ids -- `SD01-001` is 651 under
`ja-jp`, 1357 under `zh-cn` and 1139 under `ko-kr` -- so the printed code is the only key that
crosses a locale.

The code is not unique either, and the reason turned out to matter. 36 codes carry two records each,
and they are **not** two artworks of one card: they are two *rarity tiers* of it. `SD01-003` is 秧秧
at ★★★ and again at ★★★★, with different art for each. So the key is **(code, star count)**, which
is collision-free in all three locales, and not the record's rank within its code -- ranking breaks
precisely where a locale ships only one of the two tiers, and six codes do exactly that. Matching
those by rank silently paired a ★★★ record with a ★★★★ one.

Star count carries no language, which is what makes it usable as the key. Where a locale states no
rarity at all -- `zh-cn` omits it on 20 records -- the leftovers are matched to whichever tiers are
still unfilled for that code, in id order, and `verify()` is what proves the result holds: every
printing matched across locales must agree on card type, level, cost, speed and damage.

## What it cleans up

- `""` and `"-"` placeholders become absent, so nothing downstream shows a chip reading "-".
- Numeric strings become numbers.
- `rarity_name` is taken from any locale that states it. `zh-cn` omits it on 20 records while
  `ja-jp` and `ko-kr` agree, and the value is a star count, which carries no language.
- Localised vocabulary (attribute, weapon type, faction, feature, character, colour, card type) is
  resolved to the stable numeric ids `search-options` publishes, and the labels are hoisted into one
  dictionary per facet. So the file states "attribute 2" once, with its three labels, instead of
  repeating 焦熱 / 热熔 / 용융 across every card that has it.
- `feature_name` is a comma-joined list and is split into several ids.
- The two image directories in use are hoisted into `imageBases`, leaving each printing with a
  filename rather than a 150-character URL repeated three times.
"""

import collections
import json
import io
import os
import sys
import time
import urllib.request

BASE = "https://mc-api.ucp-jp.com/api/web"

# The three locales that answer with cards. `en-us` and `zh-tw` are accepted and answer 200 with an
# empty list, and bare `ko`/`kr` do the same -- which is why these are full tags and are hardcoded.
LOCALES = {"ja-jp": "ja", "zh-cn": "zh-cn", "ko-kr": "ko"}

# The card fields that carry a localised label, and the `search-options` group that enumerates it.
VOCABULARY = {
	"type_name": ("cardType", "type_id"),
	"attr_name": ("attribute", "attr_id"),
	"weapon_type_name": ("weaponType", "weapon_type_id"),
	"force_name": ("faction", "force_id"),
	"feature_name": ("feature", "feature_id"),
	"character_name": ("character", "character_id"),
	"color_name": ("color", "color_id"),
}

# A Compose Multiplatform resource, which is what gets it bundled for JVM, Android and iOS
# alike -- Kotlin Multiplatform has no standard resource API and a klib carries no files.
OUT = os.path.join(
	"providers", "wuwa", "src", "commonMain", "composeResources", "files", "wuwa-cards.json",
)


# ==================
# MARK: Transport
# ==================

def get(path, locale, **params):
	"""One request, with the locale header the site's own client sets."""
	vQuery = "&".join(f"{k}={v}" for k, v in params.items())
	vUrl = f"{BASE}/{path}" + (f"?{vQuery}" if vQuery else "")
	vRequest = urllib.request.Request(vUrl, headers={
		"x-lang": locale,
		"Accept": "application/json",
		"User-Agent": "cardbrowser-scraper (github.com/bitsycore)",
	})
	with urllib.request.urlopen(vRequest, timeout=30) as vResponse:
		vBody = json.loads(vResponse.read().decode("utf-8"))
	# `code` is an application status and is 1 on success. The transport can answer 200 while this
	# says otherwise, so trusting the HTTP status alone would record an error as an empty catalogue.
	if vBody.get("code") != 1:
		raise SystemExit(f"{vUrl} [{locale}] answered code {vBody.get('code')}: {vBody.get('msg')}")
	return vBody.get("data")


CACHE = os.path.join("build", "wuwa-scrape", "raw.json")


def scrape(use_cache=True):
	"""
	Every locale's card list, every card's detail record, and the facet vocabularies.

	Cached, because this is 357 requests against somebody else's undocumented endpoint and the
	shaping below is what gets iterated on. `--refresh` re-fetches.
	"""
	if use_cache and os.path.exists(CACHE):
		print(f"reusing {CACHE} (pass --refresh to re-fetch)", file=sys.stderr)
		with io.open(CACHE, encoding="utf-8") as vFile:
			vCached = json.load(vFile)
		# JSON object keys are strings; the detail maps are keyed by numeric card id.
		for vLocale in vCached:
			vCached[vLocale]["details"] = {
				int(k): v for k, v in vCached[vLocale]["details"].items()
			}
		return vCached

	vRaw = {}
	for vLocale in LOCALES:
		vList = get("card/list", vLocale, page=1, size=500)
		vCards = vList["list"]
		if not vCards:
			raise SystemExit(f"{vLocale} answered with an empty list -- refusing to write a snapshot")
		vDetails = {}
		for vIndex, vCard in enumerate(vCards, 1):
			vDetails[vCard["id"]] = get("card/info", vLocale, id=vCard["id"])
			# This is somebody else's undocumented endpoint. One request at a time, unhurried.
			time.sleep(0.12)
			if vIndex % 25 == 0:
				print(f"  {vLocale}: {vIndex}/{len(vCards)}", file=sys.stderr)
		vRaw[vLocale] = {
			"total": vList["total"],
			"cards": vCards,
			"details": vDetails,
			"options": get("card/search-options", vLocale),
		}
		print(f"{vLocale}: {len(vCards)} cards", file=sys.stderr)
	os.makedirs(os.path.dirname(CACHE), exist_ok=True)
	with io.open(CACHE, "w", encoding="utf-8") as vFile:
		json.dump(vRaw, vFile, ensure_ascii=False)
	return vRaw


# ==================
# MARK: Cleanup
# ==================

def value(raw):
	"""The API's `""` and `"-"` placeholders, treated as absent."""
	if raw is None:
		return None
	vText = str(raw).strip()
	return None if vText in ("", "-") else vText


def number(raw):
	vText = value(raw)
	if vText is None:
		return None
	try:
		return int(vText)
	except ValueError:
		return None


def label_ids(options):
	"""`{group: {label: id}}` for one locale, from `search-options`."""
	vMaps = {}
	for vGroup in options:
		vContent = vGroup.get("content")
		if not isinstance(vContent, list):
			continue
		vMap = {}
		for vEntry in vContent:
			if not isinstance(vEntry, dict):
				continue
			vLabel = vEntry.get("current_name", vEntry.get("name"))
			if vLabel is not None:
				vMap[str(vLabel).strip()] = vEntry["id"]
		vMaps[vGroup["param_name"]] = vMap
	return vMaps


def stars(rarity):
	"""The star count, which is the one part of `rarity_name` that carries no language."""
	vText = value(rarity)
	if vText is None:
		return None
	vCount = vText.count("★")
	return vCount if vCount else None


def products(obtain):
	"""
	The products a card is found in.

	Separated by an ideographic comma, not an ASCII one. Six cards state a product that disagrees
	with their own code prefix, so this is real information rather than a restatement of the number.
	"""
	if not obtain:
		return []
	vParts = obtain.replace("、", ",").split(",")
	return [p.strip() for p in vParts if p.strip()]


# ==================
# MARK: Alignment
# ==================

def tier_of(detail):
	"""
	The rarity tier a record sits in, as something comparable across locales.

	The star count where there is one, since `★★★` is `★★★` in every language. Otherwise the label
	itself -- "Collection" is a tier with no stars and is spelled the same in all three locales --
	and `None` when the locale states no rarity at all, which `align` then places by elimination.
	"""
	vStars = stars(detail.get("rarity_name"))
	if vStars is not None:
		return vStars
	return value(detail.get("rarity_name"))


def align(raw):
	"""
	`{(code, tier): {locale: id}}` -- one entry per printing, gathering the locales that carry it.

	Two passes per code. Records that state a tier claim it outright; records that state none are
	then dropped into whichever tiers that locale has not yet filled, in id order. That second pass
	is only ever reached for a locale that omitted the rarity, and only ever chooses between tiers
	the other locales have already established, so it cannot invent one.
	"""
	vByCode = collections.defaultdict(lambda: collections.defaultdict(list))
	for vLocale in LOCALES:
		for vCard in raw[vLocale]["cards"]:
			vDetail = raw[vLocale]["details"][vCard["id"]]
			vByCode[vCard["code"]][vLocale].append((vCard["id"], tier_of(vDetail)))

	vAligned = collections.defaultdict(dict)
	for vCode, vLocaleRecords in vByCode.items():
		# Every tier any locale is explicit about, which is the set of slots to fill.
		vTiers = sorted(
			{vTier for vRecords in vLocaleRecords.values() for _, vTier in vRecords if vTier is not None},
			key=lambda t: (isinstance(t, str), t),
		)
		for vLocale, vRecords in vLocaleRecords.items():
			vClaimed = set()
			vUnknown = []
			for vId, vTier in sorted(vRecords):
				if vTier is None:
					vUnknown.append(vId)
				else:
					vAligned[(vCode, vTier)][vLocale] = vId
					vClaimed.add(vTier)
			# Placed by elimination, and only into tiers another locale has already named. A code
			# whose every locale is silent about rarity has one record and one unnamed slot.
			vFree = [t for t in vTiers if t not in vClaimed] or [None]
			for vIndex, vId in enumerate(vUnknown):
				vTier = vFree[vIndex] if vIndex < len(vFree) else f"extra-{vIndex}"
				vAligned[(vCode, vTier)][vLocale] = vId
	return dict(vAligned)


def build(raw, resolved=None, report=None):
	vMajority = resolved or {}
	vAligned = align(raw)
	vMaps = {vLocale: label_ids(raw[vLocale]["options"]) for vLocale in LOCALES}

	# The vocabulary, keyed by the ids `search-options` publishes. Those ids are the same in every
	# locale -- verified -- which is what makes them usable as the language-free key.
	vVocabulary = {vName: {} for vName, _ in VOCABULARY.values()}
	for vLocale, vTag in LOCALES.items():
		for vField, (vName, vParam) in VOCABULARY.items():
			for vLabel, vId in vMaps[vLocale].get(vParam, {}).items():
				if vLabel == "-":
					continue
				vVocabulary[vName].setdefault(vId, {})[vTag] = vLabel

	vImageBases = []
	vCards = {}
	for (vCode, vTier), vLocaleIds in vAligned.items():
		vCard = vCards.setdefault((vCode, vTier), {
			"code": vCode,
			# The tier this printing is: a star count, or a label such as "Collection". Two records
			# under one code are the same card at two rarities, not two artworks of one rarity.
			"stars": vTier if isinstance(vTier, int) else None,
			"rarity": vTier if isinstance(vTier, str) else None,
			"set": vCode.split("-")[0],
			"products": [],
			"cardTypeId": None,
			"level": None,
			"cost": None,
			"speed": None,
			"damage": None,
			"attributeId": None,
			"weaponTypeId": None,
			"factionId": None,
			"colorId": None,
			"characterId": None,
			"featureIds": [],
			"printings": {},
		})
		for vLocale, vId in sorted(vLocaleIds.items()):
			vTag = LOCALES[vLocale]
			vDetail = raw[vLocale]["details"][vId]

			# Language-free facts. The first locale to state one wins, which is safe precisely
			# because `verify` has proved the locales never disagree about any of them -- and it is
			# what lets a value one catalogue omits be filled from another.
			def fill(key, val, card=vCard):
				if card[key] is None and val is not None:
					card[key] = val

			fill("cardTypeId", vDetail.get("type_id"))
			fill("level", number(vDetail.get("level")))
			fill("cost", number(vDetail.get("fee")))
			fill("speed", number(vDetail.get("speed")))
			fill("damage", number(vDetail.get("damage")))
			# A field the locales disagreed about is taken from `verify`'s majority rather than
			# from whichever locale this loop happened to reach first -- which, sorted, is always
			# ja-jp. That would give the right answer today by accident, and the accident is the
			# problem: it would go on being "right" if the origin locale were the odd one out.
			for vField, _ in LANGUAGE_FREE:
				if ((vCode, vTier), vField) in vMajority:
					vCard[vField] = vMajority[((vCode, vTier), vField)]

			# Where a card is actually found, which is not always the product its number was
			# assigned under: six cards state a product that disagrees with their own code prefix.
			if not vCard["products"]:
				vCard["products"] = products(vDetail.get("obtain"))

			for vField, (vName, vParam) in VOCABULARY.items():
				vText = value(vDetail.get(vField))
				if vText is None:
					continue
				vIds = []
				for vPart in [q.strip() for q in vText.split(",") if q.strip()]:
					vResolved = vMaps[vLocale].get(vParam, {}).get(vPart)
					if vResolved is not None:
						vIds.append(vResolved)
				if not vIds:
					continue
				if vName == "feature":
					vCard["featureIds"] = sorted(set(vCard["featureIds"]) | set(vIds))
				else:
					vKey = {
						"cardType": "cardTypeId", "attribute": "attributeId",
						"weaponType": "weaponTypeId", "faction": "factionId",
						"character": "characterId", "color": "colorId",
					}[vName]
					fill(vKey, vIds[0])

			vImage = value(vDetail.get("img"))
			vBase, vFile = (vImage.rsplit("/", 1) if vImage else (None, None))
			if vBase is not None and vBase not in vImageBases:
				vImageBases.append(vBase)
			vCard["printings"][vTag] = {
				"providerId": vId,
				"name": value(vDetail.get("name")),
				"rules": value(vDetail.get("info")),
				"imageBase": vImageBases.index(vBase) if vBase is not None else None,
				"image": vFile,
			}

	def order(key):
		vCode, vTier = key
		# Set, then collector number, then rarity ascending -- so the base tier of a card precedes
		# its premium one and a "Collection" tier, which has no count, sorts last.
		return (vCode.split("-")[0], vCode, isinstance(vTier, str), vTier if vTier is not None else -1)

	vOrdered = [vCards[k] for k in sorted(vCards, key=order)]
	return {
		"schemaVersion": 1,
		"source": {
			"api": BASE,
			"scrapedOn": time.strftime("%Y-%m-%d"),
			"locales": {vTag: raw[vLocale]["total"] for vLocale, vTag in LOCALES.items()},
			"note": "Unofficial snapshot of UCP's own card list. Regenerate with "
			        "providers/wuwa/tools/scrape_wuwa.py.",
			# Recorded, not hidden. Where UCP's locales disagreed about a language-free field the
			# majority was taken, and this says which card, which field, what each locale claimed
			# and what was written -- so a reader can see that a number in this file is one of two
			# the source published, and a new disagreement is visible rather than absorbed.
			"disagreements": report or [],
		},
		"imageBases": vImageBases,
		"vocabulary": {
			vName: [
				{"id": vId, "labels": vLabels}
				for vId, vLabels in sorted(vVocabulary[vName].items())
			]
			for vName in sorted(vVocabulary)
		},
		"cards": vOrdered,
	}


# The language-free fields, as (snapshot key, detail key). These are what alignment is checked
# against: two locales pointed at one entry must agree about them, because none of them is text.
LANGUAGE_FREE = (
	("cardTypeId", "type_id"),
	("level", "level"),
	("cost", "fee"),
	("speed", "speed"),
	("damage", "damage"),
)


def verify(raw):
	"""
	Checks the alignment against the card fields that carry no language, and resolves what it can.

	Two locales pointed at one entry must be describing the same card, and card type, level, cost,
	speed and damage are where that is checkable without reading any text.

	A disagreement used to abort the whole run. That was right while it had never happened and wrong
	the first time it did: on 2026-09-11 UCP's Simplified Chinese catalogue gave `BP01-049` a damage
	of 6 where Japanese and Korean both say 5, and refusing to write cost a 194-card update over one
	number. An entry merging two different cards is still worse than no snapshot -- but that is not
	what this is. Type, level, cost and speed all match; the locales plainly mean the same card and
	one of them has a typo.

	So a field with a strict majority is resolved to it and **recorded** in the snapshot's
	provenance, and anything without one still aborts: two locales disagreeing with nothing to break
	the tie is a genuine "we do not know", and this project does not guess at those.

	Returns `(resolved, report)` -- `resolved` keyed by `(alignment key, snapshot field)`, and
	`report` the human-readable account that goes into the file.
	"""
	vAligned = align(raw)
	vProblems = []
	vResolved = {}
	vReport = []
	vShared = 0
	for vKey, vLocaleIds in sorted(vAligned.items(), key=lambda kv: str(kv[0])):
		if len(vLocaleIds) < 2:
			continue
		vShared += 1
		for vField, vDetailKey in LANGUAGE_FREE:
			vSeen = {}
			for vLocale, vId in vLocaleIds.items():
				vDetail = raw[vLocale]["details"][vId]
				vRaw = vDetail.get(vDetailKey)
				vSeen[vLocale] = vRaw if vField == "cardTypeId" else number(vRaw)
			# A locale that states nothing is not disagreeing; `build` fills those from a locale
			# that does, which is the whole reason the fields are pooled.
			vStated = {k: v for k, v in vSeen.items() if v is not None}
			if len(set(vStated.values())) <= 1:
				continue
			vCounts = collections.Counter(vStated.values())
			vTop, vCount = vCounts.most_common(1)[0]
			if list(vCounts.values()).count(vCount) > 1:
				vProblems.append((vKey, vField, vSeen))
				continue
			vResolved[(vKey, vField)] = vTop
			vReport.append({
				"code": vKey[0],
				"tier": vKey[1],
				"field": vField,
				"values": {k: v for k, v in sorted(vStated.items())},
				"taken": vTop,
			})
	if vProblems:
		for vKey, vField, vSeen in vProblems[:10]:
			print(f"  UNRESOLVED {vKey} {vField}: {vSeen}", file=sys.stderr)
		raise SystemExit(
			f"{len(vProblems)} fields disagree with no majority; snapshot not written"
		)
	for vEntry in vReport:
		print(
			f"  RESOLVED {vEntry['code']} tier={vEntry['tier']} {vEntry['field']}: "
			f"{vEntry['values']} -> {vEntry['taken']}",
			file=sys.stderr,
		)
	print(f"alignment verified across {vShared} printings carried by more than one locale",
	      file=sys.stderr)
	return vResolved, vReport


def main():
	if not os.path.isdir(os.path.join("providers", "wuwa")):
		raise SystemExit("Run this from the repository root")
	vRaw = scrape(use_cache="--refresh" not in sys.argv)
	vResolved, vReport = verify(vRaw)
	vSnapshot = build(vRaw, vResolved, vReport)
	os.makedirs(os.path.dirname(OUT), exist_ok=True)
	with io.open(OUT, "w", encoding="utf-8", newline="\n") as vFile:
		json.dump(vSnapshot, vFile, ensure_ascii=False, indent="\t", sort_keys=False)
		vFile.write("\n")
	print(f"wrote {OUT}: {len(vSnapshot['cards'])} printings", file=sys.stderr)


if __name__ == "__main__":
	main()
