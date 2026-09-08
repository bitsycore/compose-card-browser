package com.bitsycore.cardbrowser.providers.riftcodex

/**
 * Real Riftcodex responses, captured from the live API.
 *
 * Copied from actual `api.riftcodex.com` output rather than written by hand, so the mapping is
 * tested against the JSON the provider really sends -- including the parts that are awkward, like
 * `cardmarket_id` being a string on one set and an array on another.
 */
internal object RiftcodexFixtures {

	/** `/sets?size=100`, trimmed to four representative records. */
	val SETS = """
	{
	  "items": [
	    {
	      "id": "69bc5bf6e195be3e561d1eb1",
	      "name": "Origins",
	      "set_id": "OGN",
	      "card_count": 352,
	      "tcgplayer_id": "24344",
	      "cardmarket_id": "6286",
	      "published_on": "2025-10-31T00:00:00"
	    },
	    {
	      "id": "69bc5bf6e195be3e561d1eb3",
	      "name": "Riftbound Organized Play Promotional Cards",
	      "set_id": "OPP",
	      "card_count": 133,
	      "tcgplayer_id": "24343",
	      "cardmarket_id": ["6322", "6483"],
	      "published_on": "2025-10-31T00:00:00"
	    },
	    {
	      "id": "6a32e5e7189085654c4f249e",
	      "name": "Vendetta",
	      "set_id": "VEN",
	      "card_count": 358,
	      "tcgplayer_id": null,
	      "cardmarket_id": null,
	      "published_on": "2026-07-31T00:00:00"
	    },
	    {
	      "id": "69bc5bf6e195be3e561d1eae",
	      "name": "Unleashed",
	      "set_id": "UNL",
	      "card_count": 280,
	      "tcgplayer_id": "24560",
	      "cardmarket_id": null,
	      "published_on": "2026-05-08T00:00:00"
	    }
	  ],
	  "total": 4, "page": 1, "size": 100, "pages": 1
	}
	""".trimIndent()

	/** A complete, ordinary card: `/cards?set_id=OGS`, first item. */
	val CARD_ORDINARY = """
	{
	  "id": "69bc5bd8d308c64675ca8816",
	  "name": "Annie - Fiery",
	  "riftbound_id": "ogs-001-024",
	  "tcgplayer_id": "653136",
	  "collector_number": 1,
	  "attributes": { "energy": 5, "might": 4, "power": 1 },
	  "classification": {
	    "type": "Unit", "supertype": "Champion", "rarity": "Epic", "domain": ["Fury"]
	  },
	  "text": {
	    "rich": "<p>Your spells and abilities deal 1 Bonus Damage.</p>",
	    "plain": "Your spells and abilities deal 1 Bonus Damage.",
	    "flavour": "I never play with matches."
	  },
	  "set": { "set_id": "OGS", "label": "Proving Grounds" },
	  "media": {
	    "image_url": "https://cmsassets.rgpub.io/sanity/images/dsfx7636/game_data_live/532d75dc36a16eb5954253a77366fcceac7aec62-744x1039.png?accountingTag=RB",
	    "artist": "Polar Engine Studio",
	    "accessibility_text": "Riftbound Unit: Annie, Fiery."
	  },
	  "tags": ["Annie", "Noxus"],
	  "orientation": "portrait",
	  "metadata": {
	    "clean_name": "Annie Fiery",
	    "updated_on": "2026-07-10T22:44:42.128286+00:00",
	    "alternate_art": false, "overnumbered": false, "signature": false
	  },
	  "new": false
	}
	""".trimIndent()

	/**
	 * The two Origins records that share collector number 299.
	 *
	 * This pair is why collector numbers are strings: the integer field is identical for both and
	 * only `riftbound_id` tells them apart, as `299` and `299*`.
	 */
	val CARDS_SHARED_COLLECTOR_NUMBER = """
	{
	  "items": [
	    {
	      "id": "ogn-299-over",
	      "name": "Kai'Sa - Daughter of the Void (Overnumbered)",
	      "riftbound_id": "ogn-299-298",
	      "tcgplayer_id": "700001",
	      "collector_number": 299,
	      "attributes": { "energy": 6, "might": 5, "power": 2 },
	      "classification": { "type": "Unit", "supertype": "Champion", "rarity": "Epic", "domain": ["Chaos"] },
	      "text": { "rich": "<p>x</p>", "plain": "x", "flavour": null },
	      "set": { "set_id": "OGN", "label": "Origins" },
	      "media": {
	        "image_url": "https://cmsassets.rgpub.io/sanity/images/dsfx7636/game_data_live/over-744x1039.png?accountingTag=RB",
	        "artist": "Artist A", "accessibility_text": "over"
	      },
	      "tags": ["Kai'Sa"], "orientation": "portrait",
	      "metadata": {
	        "clean_name": "KaiSa Daughter of the Void", "updated_on": null,
	        "alternate_art": false, "overnumbered": true, "signature": false
	      }
	    },
	    {
	      "id": "ogn-299-sig",
	      "name": "Kai'Sa - Daughter of the Void (Signature)",
	      "riftbound_id": "ogn-299*-298",
	      "tcgplayer_id": "700002",
	      "collector_number": 299,
	      "attributes": { "energy": 6, "might": 5, "power": 2 },
	      "classification": { "type": "Unit", "supertype": "Champion", "rarity": "Epic", "domain": ["Chaos"] },
	      "text": { "rich": "<p>x</p>", "plain": "x", "flavour": null },
	      "set": { "set_id": "OGN", "label": "Origins" },
	      "media": {
	        "image_url": "https://cmsassets.rgpub.io/sanity/images/dsfx7636/game_data_live/sig-744x1039.png?accountingTag=RB",
	        "artist": "Artist B", "accessibility_text": "sig"
	      },
	      "tags": ["Kai'Sa"], "orientation": "portrait",
	      "metadata": {
	        "clean_name": "KaiSa Daughter of the Void", "updated_on": null,
	        "alternate_art": false, "overnumbered": false, "signature": true
	      }
	    }
	  ],
	  "total": 2, "page": 1, "size": 100, "pages": 1
	}
	""".trimIndent()

	/**
	 * A record stripped to almost nothing, plus an unknown field.
	 *
	 * Riftcodex describes itself as an active work in progress, so the mapping has to survive both a
	 * field it has never seen and a field it expected that is missing.
	 */
	val CARD_SPARSE = """
	{
	  "id": "sparse-1",
	  "name": "Mystery Card",
	  "riftbound_id": "",
	  "collector_number": null,
	  "attributes": {},
	  "classification": { "type": "Spell", "rarity": "Common", "domain": [] },
	  "text": {},
	  "set": { "set_id": "ogn", "label": "Origins" },
	  "media": {},
	  "tags": [],
	  "metadata": {
	    "clean_name": null, "updated_on": null,
	    "alternate_art": false, "overnumbered": false, "signature": false
	  },
	  "some_field_added_next_month": { "nested": [1, 2, 3] }
	}
	""".trimIndent()

	/** Page 1 of a two-page result, so `hasMore` can be checked against the server's own count. */
	val PAGE_ONE_OF_TWO = """
	{ "items": [$CARD_ORDINARY], "total": 150, "page": 1, "size": 100, "pages": 2 }
	""".trimIndent()

	/** Page 2 of the same, where `hasMore` must be false. */
	val PAGE_TWO_OF_TWO = """
	{ "items": [$CARD_ORDINARY], "total": 150, "page": 2, "size": 100, "pages": 2 }
	""".trimIndent()

	/** The real 422 body when `size` exceeds the server's cap of 100. */
	val PAGE_SIZE_REJECTED = """
	{"detail":[{"type":"less_than_equal","loc":["query","size"],
	"msg":"Input should be less than or equal to 100","input":"400","ctx":{"le":100}}]}
	""".trimIndent()
}
