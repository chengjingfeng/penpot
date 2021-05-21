;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.import
  (:require
   [tubax.core :as tubax]
   [promesa.core :as p]
   [app.worker.impl :as impl]
   ["jszip" :as zip]))

(defn process-entry [path entry]
  (when-not (.-dir entry)
    (p/then
     (.async entry "text")
     (fn [svg]
       (let [import-data (tubax/xml->clj svg)]
         (.log js/console path (clj->js import-data)))))))

(defmethod impl/handler :import-file
  [{:keys [files]}]

  (doseq [file files]
    (p/let [response (js/fetch file)
            data     (.blob response)
            content  (zip/loadAsync data)]
      (.forEach content process-entry)))
  
  {:result "OK"})
