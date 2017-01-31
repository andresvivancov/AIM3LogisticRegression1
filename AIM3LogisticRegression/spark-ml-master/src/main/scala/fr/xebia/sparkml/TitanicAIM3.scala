package fr.xebia.sparkml

import java.io.File

import org.apache.commons.io.FileUtils
import org.apache.spark.ml.classification.LogisticRegression
import org.apache.spark.ml.evaluation.BinaryClassificationEvaluator
import org.apache.spark.ml.feature._
import org.apache.spark.ml.tuning.{CrossValidator, ParamGridBuilder}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types._
import org.apache.spark.{SparkConf, SparkContext}

object TitanicAIM3 {

  def main(args: Array[String]) {

    val timeini = System.currentTimeMillis()
    FileUtils.deleteDirectory(new File("src/main/resources/titanic/result"))

    val conf = new SparkConf().setAppName("TitanicAIM3").setMaster("local[1]")

    val sc = new SparkContext(conf)

    val sqlContext = new SQLContext(sc)
    // We use the $ operator from implicit class StringToColumn
    import sqlContext.implicits._

    // Load the train.csv file as a DataFrame
    // Use the spark-csv library see https://github.com/databricks/spark-csv
    val csv = sqlContext.read.format("com.databricks.spark.csv").option("header", "true").load("src/main/resources/titanic/train.csv")

    // spark-csv put the type StringType to each column
    csv.printSchema()

    // select only the useful columns, rename them and cast them to the right type
    val df = csv.select(
      $"Survived".as("label").cast(DoubleType),
      $"Age".as("age").cast(IntegerType),
      $"Fare".as("fare").cast(DoubleType),
      $"Pclass".as("class").cast(DoubleType),
      $"Sex".as("sex"),
      $"Name".as("name")
    )

    // verify the schema
   println("Printing Schema")
    df.printSchema()

    // look at the data
    println("Printing show")
    df.show()

    // show stats for each column
    println("Stat for each column")
    df.describe(df.columns: _*).show()


//    val sexIndexerModel = sexIndexer.fit(df)
//    val sexIndexed = sexIndexerModel.transform(df)

    // We replace the missing values of the age and fare columns by their mean.
    val select = df.na.fill(Map("age" -> 30, "fare" -> 32.2))


    // The stages of our pipeline
    val sexIndexer = new StringIndexer().setInputCol("sex").setOutputCol("sexIndex")
    val classEncoder = new OneHotEncoder().setInputCol("class").setOutputCol("classVec")
    val tokenizer = new Tokenizer().setInputCol("name").setOutputCol("words")
    val hashingTF = new HashingTF().setInputCol(tokenizer.getOutputCol).setOutputCol("hash")
    val vectorAssembler = new VectorAssembler().setInputCols(Array("hash", "age", "fare", "sexIndex", "classVec"))
                              .setOutputCol("features")

    // We will train our model on 75% of our data and use the 25% left for validation.
    val Array(trainSet, validationSet) =
      select.randomSplit(Array(0.75, 0.25))

    val logisticRegression = new LogisticRegression()
    val pipeline = new Pipeline().setStages(Array(sexIndexer, classEncoder, tokenizer, hashingTF,
                                                  vectorAssembler, logisticRegression))

    // We will cross validate our pipeline
    val crossValidator = new CrossValidator().setEstimator(pipeline).
      setEvaluator(new BinaryClassificationEvaluator)

    // Here are the params we want to validationPredictions
    val paramGrid = new ParamGridBuilder()
      .addGrid(hashingTF.numFeatures, Array(2, 5,5))  // 2 5 1000
      .addGrid(logisticRegression.regParam, Array(1, 0.1 , 0.01 , 0.02)) // 1 0.1 0.01 //the are like weights, per dimension or feauture, it is part of optimizer
      .addGrid(logisticRegression.maxIter, Array(10)) //10 50 100
      .build()
    crossValidator.setEstimatorParamMaps(paramGrid)

    // We will use a 3-fold cross validation
    crossValidator.setNumFolds(15)  //3

    println("Cross Validation")
    val cvModel = crossValidator.fit(trainSet)


    println("Best model")
    for (stage <- cvModel.bestModel.asInstanceOf[PipelineModel].stages)
              println(stage.explainParams())

    println("Evaluate the model on the validation set.")
    val validationPredictions = cvModel.transform(validationSet)

    // Area under the ROC curve for the validation set

    val binaryClassificationEvaluator: BinaryClassificationEvaluator =
      new BinaryClassificationEvaluator()
    println(s"${binaryClassificationEvaluator.getMetricName} " +
      s"      ${binaryClassificationEvaluator.evaluate(validationPredictions)}")

    // We want to print the percentage of passengers we correctly predict on the validation set
    val total = validationPredictions.count()
    val goodPredictionCount = validationPredictions.filter(validationPredictions("label")
      === validationPredictions("prediction")).count()
    println(s"correct prediction percentage : ${goodPredictionCount / total.toDouble}")


    // Lets make prediction on new data where the label is unknown
    println("Predict validationPredictions.csv passengers fate")
    val csvTest = sqlContext.read.format("com.databricks.spark.csv").
      option("header", "true").load("src/main/resources/titanic/test.csv")

    val dfTest = csvTest.select(
      $"PassengerId",
      $"Age".as("age").cast(IntegerType),
      $"Fare".as("fare").cast(DoubleType),
      $"Pclass".as("class").cast(DoubleType),
      $"Sex".as("sex"),
      $"Name".as("name")
    ).coalesce(1)

    val selectTest = dfTest.na.fill(Map("age" -> 30, "fare" -> 32.2))

    //
    val result = cvModel.transform(selectTest)

    // let's write the result in the correct format for Kaggle
    result.select($"PassengerId", $"prediction".cast(IntegerType))
      .write.format("com.databricks.spark.csv").save("src/main/resources/titanic/result")

    val timeend = System.currentTimeMillis()

    val totaltime = timeend - timeini
    println("total time is" + totaltime)
  }


}
