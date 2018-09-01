# Example

Install [Leiningen](https://leiningen.org).  Then, create a new example application called `myproj` from the `template` directory.

```
lein new psl-clojure myproj
cd myproj
```

Start the Clojure REPL with `lein repl`.  A prompt will appear: 

`myproj.core=> `

The prompt shows that you are in the namespace for the `src/myproj/core.clj` file.  If you take a look in that file, you'll find a small PSL model we'll use in this example.  At the prompt, run the following to create a PSL data store and model. 

```
(def cb (config-bundle))
(def ds (data-store cb))
(def m (psl/model-new ds))
```

Add the predicates and rules for a simple problem to the model.  The predicates and rules rule are defined in `src/myproj/core.clj`.

```
(predicates m)
(rules m ds)
```

Running `(psl/model-print m)` will print the current model, which predicts who knows whom in a small group of people.  Some of the answer is known; load it from `data/knows_obs.txt`.

```
(def knows-obs (ini/read-dataset "data/knows_obs.txt" :delim '\tab :header true))
```

Running `knows-obs` will print the dataset.

```
|    :p1 |    :p2 |
|--------+--------|
|    Ben |  Elena |
|    Ben | Dhanya |
|   Arti |   Alex |
| Sabina | Dhanya |
```

Create a PSL partition to hold this observed data, then write the dataset to that partition.

```
(def obs (psl/partition-new ds))
(psl/pred-append ds obs (psl/p m "knows") knows-obs)
```

You can look up documentation for any function, e.g., `(doc psl/pred-append)`.  You can run `(psl/pred-read m ds [obs] "knows" true)` to check that the data is written to PSL and that all observations have truth value `1.0`.  Load the remaining observed data.

```
(def likes-obs (ini/read-dataset "data/likes_obs.txt" :delim '\tab :header true))
(def lived-obs (ini/read-dataset "data/lived_obs.txt" :delim '\tab :header true))
(psl/pred-append ds obs (psl/p m "likes") likes-obs)
(psl/pred-append ds obs (psl/p m "lived") lived-obs)
```

Make another partition to hold the results of inference, then run inference.

```
(def target (psl/partition-new ds))
(def knows-targets (ini/read-dataset "data/knows_targets.txt" :delim '\tab :header true))
(psl/pred-append ds target (psl/p m "knows") knows-targets)
(def closed-preds ["lived" "likes"])
(inference cb ds m obs target closed-preds)
```

Print five atoms with the highest truth value in the MPE state. 

```
(in/sel
 (in/$order [:value] :desc
            (psl/pred-read m ds [target] "knows" true))
 :rows (range 5))
```

```
|    :p1 |    :p2 |             :value |
|--------+--------+--------------------|
|   Alex |   Arti | 0.9981735348701477 |
| Dhanya | Sabina | 0.9980729818344116 |
|  Elena |    Ben | 0.9930140972137451 |
| Dhanya |    Ben | 0.9921430349349976 |
|  Elena |   Alex | 0.6123796105384827 |
```

Print a sample of the ground model:

```
(psl/ground-rules-print-summary
   (psl/ground-rules-stratified-sample grs 1))
```

Load the correct answer and evaluate the result of the model.

```
(def knows-truth (ini/read-dataset "data/knows_truth.txt" :delim '\tab :header true))
(def truth (psl/partition-new ds))
(psl/pred-append ds truth (psl/p m "knows") knows-truth)

(let [evaluator (ContinuousEvaluator.)
      rv-db (let [dummy-write (psl/partition-new ds)]
              (psl/open-db ds m [target] dummy-write closed-preds))
      truth-db (let [dummy-write (psl/partition-new ds)]
                 (psl/open-db ds m [truth] dummy-write closed-preds))
      open-pred (psl/p m "knows")]
  (.compute evaluator rv-db truth-db open-pred)
  (prn (.toString (.getAllStats evaluator))))
```

```
"MAE: 0.440447, MSE: 0.240337"
```

Save the result to a file, sorted by truth value.

```
(save (in/$order [:value] :desc
                 (psl/pred-read m ds [target] "knows" true))
      "knows.txt")
```
