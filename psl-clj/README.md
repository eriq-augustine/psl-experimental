# Example

Install [Leiningen](https://leiningen.org).  Then, create a new example application called `myproj` from the `template` directory.

```
lein new psl-clj myproj
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

Apply a rule for a toy problem to the model.  The rule, which is defined in `core.clj`, says that all dogs are mammals.

```
(VOCAB m)
(DOGS-ARE-MAMMALS m 1.0 false)
```

Running `(println m)` will print the current model: `{1.0} DOG(N) >> MAMMAL(N)`.  Create a dataset of two dogs.

```
(def dogs (in/dataset [:n] [["Fido"] ["Furry"]]))
```

Running `dogs` will print the dataset.

```
|    :n |
|-------|
|  Fido |
| Furry |
```

Create a PSL partition to hold this observed data, then write the dataset to that partition.

```
(def obs (psl/partition-new))
(psl/pred-append ds obs (psl/p m "dog") dogs)
```

You can look up documentation for any function, e.g., `(doc psl/pred-append)`.  You can run `(psl/pred-read m ds [obs] "dog" true)` to check that the data is written to PSL.  Make another partition to hold the results of inference, then run inference.

```
(def res (psl/partition-new))
(inference cb ds m obs res ["dog"])
```

Confirm that all dogs are mammals with `(psl/pred-read m ds [res] "mammal" true)`.  Each atom has a truth value of `1.0`.

```
|    :n | :value |
|-------+--------|
| Furry |    1.0 |
|  Fido |    1.0 |
```

This is an interactive session, so we can change the data and rules as we like.  Suppose there's a third animal but we're only 50% sure it's a dog.

```
(def more-dogs (in/dataset [:n :value] [["George" 0.5]]))
(psl/pred-append ds obs (psl/p m "dog") more-dogs)
(inference cb ds m obs res ["dog"])
```

Now `(psl/pred-read m ds [res] "mammal" true)` shows the updated inference result.

```
|     :n | :value |
|--------+--------|
|  Furry |    1.0 |
|   Fido |    1.0 |
| George |    0.5 |
```

Also, for some reason we think mammals are rare.  Add a prior with weight `0.75` saying this.

```
(psl/add-rule m (psl/NOT (mammal 'N)) 0.75 true "MAMMALS ARE RARE")
```

Update inference and check the result.

```
(inference cb ds m obs res ["dog"])
(psl/pred-read m ds [res] "mammal" true)
```

```
|     :n |              :value |
|--------+---------------------|
|  Furry |          0.66667873 |
|   Fido |          0.66667873 |
| George | 0.49900774400000003 |
```

To see the final ground model, run `(psl/ground-rules-print-summary grs)`.
