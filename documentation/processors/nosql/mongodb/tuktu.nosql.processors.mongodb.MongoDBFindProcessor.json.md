### tuktu.nosql.processors.mongodb.MongoDBFindProcessor
Executes a query with a filter on a given list of nodes.

  * **id** *(type: string)* `[Required]`

  * **result** *(type: string)* `[Required]`

  * **config** *(type: object)* `[Required]`

    * **hosts** *(type: array)* `[Required]`
    - A list of node names, like node1.foo.com:27017. Port is optional, it is 27017 by default.

      * **[UNNAMED]** *(type: string)* `[Required]`

    * **database** *(type: string)* `[Required]`
    - The database name.

    * **collection** *(type: string)* `[Required]`
    - The name of the collection to open.

    * **query** *(type: JsObject)* `[Required]`
    - Find the documents matching these given criteria.

    * **filter** *(type: JsObject)* `[Optional]`
    - Filter results by this projection.

    * **sort** *(type: JsObject)* `[Optional]`
    - Sort results by this projection.

    * **limit** *(type: int)* `[Optional]`
    - Limit results by this number.

