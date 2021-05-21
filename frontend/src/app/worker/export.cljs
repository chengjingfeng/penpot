;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.export
  (:require
   [rumext.alpha :as mf]
   [beicon.core :as rx]
   #_[promesa.core :as p]
   #_[app.main.fonts :as fonts]
   [app.main.exports :as exports]
   #_[app.worker.impl :as impl]
   [app.util.http :as http]
   ["react-dom/server" :as rds]
   
   [promesa.core :as p]
   [app.worker.impl :as impl]
   ["jszip" :as JSZip]

   [app.config :as cfg]
   [promesa.core :as p]
   [app.main.fonts :as fonts]
   [clojure.set :as set]
   ))

(defn zip-file
  [zip]
  (let [options #js {"type" "blob"}]
    (p/then
     (.generateAsync zip options)
     (fn [blob]
       (js/URL.createObjectURL blob)))))

(defn add-to-zip
  [zip {:keys [id file-name name markup] :as page}]
  (.file zip (str file-name "/" name ".svg") markup))

(defn- handle-response
  [response]
  (cond
    (http/success? response)
    (rx/of (:body response))

    (http/client-error? response)
    (rx/throw (:body response))

    :else
    (rx/throw {:type :unexpected
               :code (:error response)})))

(defn text? [{type :type}]
  (= type :text))

(defn get-image-data [shape]
  (cond
    (= :image (:type shape))
    [(:metadata shape)]

    (some? (:fill-image shape))
    [(:fill-image shape)]

    :else
    []))

(defn populate-images-cache [data]
  (let [images (->> (:objects data)
                    (vals)
                    (mapcat get-image-data))]
    (p/create
     (fn [resolve reject]
       (rx/on-complete
        (->> (rx/from images)
             (rx/map #(cfg/resolve-file-media %))
             (rx/flat-map http/fetch-data-uri))
        resolve)))))

(defn populate-fonts-cache [data]
  (let [texts (->> (:objects data)
                   (vals)
                   (filterv text?)
                   (mapv :content)) ]

    (p/create
     (fn [resolve reject]

       (rx/on-complete
        (->> (rx/from texts)
             (rx/map fonts/get-content-fonts)
             (rx/reduce set/union #{})
             (rx/flat-map identity)
             (rx/flat-map fonts/fetch-font-css)
             (rx/flat-map fonts/extract-fontface-urls)
             (rx/flat-map http/fetch-data-uri))
        resolve)))))

(defn render-page
  [{:keys [file-name data]}]
  (-> (p/all
       [(populate-images-cache data)
        (populate-fonts-cache data)])
      (p/then
       (fn []
         (let [{:keys [id name]} data
               elem   (mf/element exports/page-svg #js {:data data :embed? true})
               markup (rds/renderToStaticMarkup elem)]
           {:id id
            :name name
            :file-name file-name
            :markup markup})))))

(defn query-file [file-id]
  (prn "??query-file" file-id)
  (->> (http/send! {:uri "/api/rpc/query/file"
                    :query {:id file-id}
                    :method :get})
       (rx/map http/conditional-decode-transit)
       (rx/mapcat handle-response)
       (rx/flat-map (fn [file]
                       (.log js/console "file" (clj->js file))
                       (let [pages (get-in file [:data :pages])
                             pages-index (get-in file [:data :pages-index])]
                         (->> pages
                              (map #(hash-map
                                     :file-name (:name file)
                                     :data (get pages-index %)))))))
       
       (rx/flat-map render-page)))

(defmethod impl/handler :export-file
  [{:keys [team-id files] :as message}]

  (p/create
   (fn [resolve reject]
     (->> (rx/from (->> files (mapv :id)))
          (rx/merge-map query-file)
          (rx/tap #(.log js/console "?? page" (clj->js %) ))
          (rx/reduce add-to-zip (JSZip.))
          (rx/flat-map zip-file)
          (rx/subs resolve (fn [error] (.error js/console "error" error) (reject error)))))))
