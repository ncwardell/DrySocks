package mobile

import (
	"math/rand/v2"

	"drysocks/engine/scrape"
)

func shuffle(c []scrape.Candidate) {
	rand.Shuffle(len(c), func(i, j int) { c[i], c[j] = c[j], c[i] })
}
