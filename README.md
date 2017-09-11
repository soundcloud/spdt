Streaming Parallel Decision Tree
================================

![tree!](https://github.com/soundcloud/SPDT/blob/master/config/tree.png?raw=true)

A streaming parallel decision tree (SPDT) enables parallelized training of a [decision tree classifier](https://en.wikipedia.org/wiki/Decision_tree_learning) and the ability to make updates to this tree with streaming data. Basic MDL-based pruning is also implemented to prevent overfitting.

The SPDT algorithm is described by [Ben-Haim and Tom-Tov 2010](http://jmlr.org/papers/volume11/ben-haim10a/ben-haim10a.pdf?origin=publication_detail).

Minimum Description Length (MDL) pruning is desribed by [Mehta et. al 1995](http://www.almaden.ibm.com/cs/projects/iis/hdb/Publications/papers/kdd95_mdl.pdf).


## Command-line interface

SPDT has a command-line interface (CLI) for classification experiments and producing models. For an overview of the CLI options, use the `--help` option. A more detailed description of each option follows.

### --model-path
You must specify a model path for model output or updates.

In the case that there is no existing model file at the path specified, a new model will be trained and written to this path with the date appended to the end of the filename.

In the case that the path points to an existing model file this model is used for one of the following operations:

* if `--training-data` specifies a data file, the model will be updated with the samples inside.
* if `--test-data` specifies a data file, the model will be used to classify the samples inside.
* if `--print` specifies a feature map file, a `dot` file will be produced using the feature map.

### --class-labels
Specify the observable class labels in the dataset in a comma-separated list. SPDT defaults to binary classification and adapts automatically to the sets `0,1` or `-1,1`.

### --error-weight
Set a weight on classification errors used during MDL pruning. Higher weights penalize errors more than lower weights. Higher weights result in more complicated trees than those resulting from lower weights.

### --real-features, --boolean-features
By default, SPDT assumes all features are real-valued. If your dataset is comprised solely of boolean variables, you must enable them in SPDT:

```
./bin/spdt --boolean-features <other_opts>
```

If your dataset contains a mix of boolean and real-valued features, you must specify which are real-valued in a comma-separated list and enable boolean features:

```
./bin/spdt --boolean-features --real-features 0,1,2 <other_opts>
```

### --impurity-limit
Specify the maximum class [entropy](https://en.wikipedia.org/wiki/Entropy_\(information_theory\)) allowable at leaf nodes. Decision trees continue to split samples at a node until the impurity criteria, which is a threshold on the class entropy at that node, is satisfied. By default, SPDT adds decisions until the training data is completely classified or further separation of the classes is impossible based on the features in the dataset.

### --min-delta
Specify the minimum change in entropy neccessary for adding a new decision at a leaf node in the tree. By default, any decrease in entropy results in a new decision.

### --min-feature-frequency
Specify a minimum relative frequency of boolean features before they are added to the model. Underrepresented boolean features might be a computational burden if too many exist in the dataset. Furthermore, they might not improve classification performance. The minimum feature frequency enables you to filter these features out.

### --training-iterations
Specify the maximum number of times that the algorithm can attempt to expand leaf nodes into new decisions. By setting the training iterations, you also set a limit on the maximum depth of the tree.

### --print
Specify a feature-map file for generating a `dot` file with a graph that represents a trained model. In the resulting graph, nodes are labeled with a period-separated concatenation of the node's ID, feature, and class label. For example:

![dot!](https://github.com/soundcloud/SPDT/blob/master/config/dottree.png?raw=true)

To generate the preceding graph, first provide a `tsv` feature-map file that maps feature indexes from the `LIBSVM` sparse data format to strings:

```
$ head -n 3 feature_map.tsv
1       Regular_insulin_dose
2       NPH_insulin_dose
3       UltraLente_insulin_dose
```

Next, call spdt with the print option:

```
$ ./bin/spdt --model-path diabetes_2015-01-20_143112.spdt --print feature_map.tsv
INFO Loading model: diabetes_2015-01-20_143112.spdt
INFO dotfile written to ./spdt.dot
$
```

Lastly, render the image with the `dot` program:

```
$ dot -Tpng -o ~/Desktop/dottree.png ./spdt.dot
```

### Data format

The SPDT CLI accepts training and testing data in [LIBSVM format](https://stats.stackexchange.com/questions/61328/libsvm-data-format):

```
<classLabel: Int> <feature1: Int>:<value1: Double> <feature2: Int>:<value2: Double> ...
```

The following example specifies a sample from the class `1`. This sample has features `0`, `1`, and `6` with corresponding values `0.5`, `0.75`, and `0.9`:

```
+1 0:0.5 1:0.75 6:0.9
```

## Serving layer

SPDT provides a basic HTTP API that enables classification and model updates. By default, models are loaded from and saved to [HDFS](https://en.wikipedia.org/wiki/Apache_Hadoop#HDFS).

### Running
The serving layer loads the most recent model from the HDFS directory that is specified by the environment variable `SPDT_DIRECTORY`. New versions of the model that result from requests to the `/update` endpoint are saved in this directory as snapshots.

You must also specify the port for the API to serve on using `WEB_PORT` and the base url for a web HDFS service for model storage with `WEB_HDFS`.

```bash
SPDT_DIRECTORY='/tmp/dir' WEB_HDFS_BASE_URL='http://localhost/webhdfs/v1' WEB_PORT=5000 ./bin/serve
```

### /classify
To classify a sample, send a POST request with a sample represented in JSON to the `/classify` endpoint. Format the sample JSON as follows:

```
{"features":[<feature1: Int>,<feature2: Int>,...],"values":[<value1: Double>, <value2: Double>,...]}
```

The following sample has features `1` and `2` with corresponding values `0.1` and `0.2`:

```
{"features":[1,2],"values":[0.1,0.2]}
```

You can use `curl` to see how the `classify` request works:

```
$ SAMPLE='{"features":[1,2],"values":[0.1,0.2]}'
$ curl -H "Content-Type: application/json" -d "$SAMPLE" -XPOST http://localhost:5000/classify ; echo
{"endpoint":"/classify","label":0}
$
```

### /update
To update the model, send samples in a POST request to the `/update` endpoint. Format the request JSON as follows:

```
{"samples":[<sample1: Sample>, <sample2: Sample>,...]}
```

Format each sample as follows. Note that a `label` field has been added:

```
{"label":<label: Int>,"features":[<feature1: Int>,<feature2: Int>,...],"values":[<value1: Double>, <value2: Double>,...]}
```

A complete example follows:

```
{"samples":[{"label":1,"features":[1,2],"values":[0.1,0.2]},{"label":0,"features":[3,4],"values":[0.3,0.4]}]}
```

You can use `curl` to see how the `update` request works:

```
$ SAMPLE='{"samples":[{"features":[1,2],"values":[0.1,0.2],"label":1},{"features":[3,4],"values":[0.3,0.4],"label":0}]}'
$ curl -H "Content-Type: application/json" -d "$SAMPLE" -XPOST http://localhost:5000/update ; echo
{"endpoint":"/update","num_samples":2,"updateId":0}
$
```

The resulting model is saved to HDFS in the directory that you specified with the `SPDT_DIRECTORY` environment variable. The current date is automatically appended to each model's filename. This enables any new instance of the serving layer to load the most recent model.

## Copyright

Copyright (c) 2013 [SoundCloud Ltd.](http://soundcloud.com) | [Trust, Safety
& Security Team](mailto:sketchy@soundcloud.com).

See the [LICENSE](LICENSE) file for details
