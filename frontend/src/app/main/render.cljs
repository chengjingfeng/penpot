(ns app.main.render
  (:require
   [app.main.exports :as svg]))


(defn render-page
  []
  (.log js/console "render-page"))

(defn render-file
  []
  (.log js/console "render-file"))

(defn exports []
  #js {:render-page render-page
       :render-file render-file})
