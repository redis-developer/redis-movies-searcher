# Building Context-Aware Applications with Java, Vector Searches, and Redis

Welcome to JNation 2025 👋🏻

![jnation.png](images/jnation.png)

On behalf of the [JNation](https://jnation.pt) organizers, it's my genuine pleasure to be your instructor today. My name is [Ricardo Ferreira](https://github.com/riferrei), and I lead the developer relations team at [Redis](https://redis.io).

In this workshop, you will learn how to design and develop an application that makes use of vector databases to handle semantic search. You will learn how to prepare the dataset for storage, how to process JSON documents and generate embeddings, how to index them into Redis, and how to implement searches and aggregations on top of it. You must be comfortable with [Java](https://www.java.com/en) and have a basic understanding of [Redis](https://redis.io/open-source).

## Part 1: Getting everything ready

The goal of this section is to get the moving parts from this project ready to be used. You will take care of the steps necessary to get the application running. This means you will make sure the application dependencies are ready, build the code from scratch, and make sure the dataset the application will handle is in a good shape.

### Task 1: Installing dependencies

#### ⏰ Estimated time: **15 minutes**

TBD.

### Task 2: Building and running the application

#### ⏰ Estimated time: **20 minutes**

TBD.

### Task 3: Importing the dataset into Redis

#### ⏰ Estimated time: **5 minutes**

TBD.

### Task 4: Finding and removing duplicated movies

#### ⏰ Estimated time: **10 minutes**

TBD.

### Task 5: Fixing data glitches in the dataset

#### ⏰ Estimated time: **10 minutes**

TBD.

## Part 2: Preparing the dataset for searches

In this section, you will implement a functionality that will prepare the dataset to be used for searches. This means you will need to process the existing dataset in such a way that creates the building blocks required for the searches, such as schemas, indexes, and embeddings.

### Task 1: Persistence layer for the existing data

#### ⏰ Estimated time: **10 minutes**

TBD.

### Task 2: Persistence layer for the generated data

#### ⏰ Estimated time: **10 minutes**

TBD.

### Task 3: Implementing the embedding generation

#### ⏰ Estimated time: **40 minutes**

TBD.

## Part 3: Implementing the Searches

In this section, you will implement the search functionality in the application. This includes allowing users to search for movies using details of the movies like title and actors, as well as search based on the plot. You will implement the code to optimize the searches for maximum efficiency, combining different search strategies and caching computationally expensive operations when necessary.

### Task 1: Implementing the Full-Text Search

#### ⏰ Estimated time: **15 minutes**

TBD.

### Task 2: Implementing the Vector Similarity Search

#### ⏰ Estimated time: **30 minutes**

TBD.

### Task 3: Optimizing the Vector Similarity Search

#### ⏰ Estimated time: **15 minutes**

TBD.

## Part 4: Moving the dataset to the cloud

In this section, you will replace your local database with one running in the cloud. You will replicate the entire data from one database to another and change the application to point to the new database. By the end, you should be able to use the application just like before with no breaking changes.

### Task 1: Create an account on Redis Cloud

#### ⏰ Estimated time: **5 minutes**

TBD.

### Task 2: Create an 30MB free database

#### ⏰ Estimated time: **5 minutes**

TBD.

### Task 3: Replicate the entire dataset

#### ⏰ Estimated time: **10 minutes**

TBD.

### Task 4: Point the application to the cloud

#### ⏰ Estimated time: **10 minutes**

TBD.
