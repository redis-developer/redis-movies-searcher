#!/bin/bash

# Drop existing index if it exists (ignore error if it doesn't exist)
redis-cli FT.DROPINDEX movie_index 2>/dev/null

# Create the movie_index index
redis-cli FT.CREATE movie_index ON HASH PREFIX 1 "movie:" SCHEMA \
  title TEXT WEIGHT 1.0 \
  year NUMERIC SORTABLE \
  plot TEXT WEIGHT 1.0 \
  releaseDate TAG SEPARATOR "|" \
  rating NUMERIC SORTABLE \
  actors TAG SEPARATOR "|" \
  plotEmbedding VECTOR FLAT 6 TYPE FLOAT32 DIM 384 DISTANCE_METRIC COSINE

echo "Index 'movie_index' created successfully."
