(ns tailrecursion.hiprepl
  (:require [clojure.java.io :as io]
            [clojail.core    :refer [sandbox safe-read]]
            [clojail.testers :refer [secure-tester]]
            [clojure.data.json :as json]
            [clj-http.client :as client]
            [clj-time.core :as time]
            [clj-time.format :as timef]
            [clj-time.coerce :as timec])
  (:import
   [java.io StringWriter]
   [org.jivesoftware.smack ConnectionConfiguration XMPPConnection XMPPException PacketListener]
   [org.jivesoftware.smack.packet Message Presence Presence$Type]
   [org.jivesoftware.smackx.muc MultiUserChat])
  (:gen-class))

(defn packet-listener [conn processor]
  (reify PacketListener
    (processPacket [_ packet]
      (processor conn packet))))

(defn message->map [#^Message m]
  (try
   {:body (.getBody m)}
   (catch Exception e (println e) {})))

(defn with-message-map [handler]
  (fn [muc packet]
    (let [message (message->map #^Message packet)]
      (try
       (handler muc message)
       (catch Exception e (println e))))))

(defn wrap-responder [handler]
  (fn [muc message]
    (if-let [resp (handler message)]
      (.sendMessage muc resp))))

(defn connect
  [username password resource]
  (let [conn (XMPPConnection. (ConnectionConfiguration. "chat.hipchat.com" 5222))]
    (.connect conn)
    (try
      (.login conn username password resource)
      (catch XMPPException e
        (throw (Exception. "Couldn't log in with user's credentials."))))
    (.sendPacket conn (Presence. Presence$Type/available))
    conn))

(defn join
  [conn room room-nickname handler]
  (let [muc (MultiUserChat. conn (str room "@conf.hipchat.com"))]
    (.join muc room-nickname)
    (.addMessageListener muc
                         (packet-listener muc (with-message-map (wrap-responder handler))))
    muc))

(def secure-sandbox (sandbox secure-tester))

(defn eval-handler
  [{:keys [body] :as msg}]
  (when (.startsWith body ",")
    (try
      (let [output (StringWriter.)]
        (secure-sandbox `(pr ~(safe-read (.substring body 1)))
                        {#'*out* output
                         #'*err* output
                         #'*print-length* 30})
        (.toString output))
      (catch Throwable t
        (.getMessage t)))))

(defn loop-listen [auth-token rooms room-nickname last-timestamp]
  (let [req (client/get (str "https://api.hipchat.com/v1/rooms/history?format=json&date=recent&auth_token=" auth-token "&room_id=" (first rooms)))
        reset (int (- (java.lang.Long/parseLong ((:headers req) "x-ratelimit-reset")) (/ (java.lang.System/currentTimeMillis) 1000)))
        remaining (java.lang.Integer/parseInt ((:headers req) "x-ratelimit-remaining"))
        msgs (:messages (json/read-str (:body req) :key-fn keyword))
        new-last-timestamp (last (sort-by timec/to-long (map #(timef/parse (timef/formatter "yyyy-MM-dd'T'HH:mm:ssZ") (:date %)) msgs)))
        msgs-valid (filter #(time/after? (timef/parse (timef/formatter "yyyy-MM-dd'T'HH:mm:ssZ") (:date %)) last-timestamp) msgs)
        clojure-reqs (filter #(.startsWith (:message %) ",") msgs-valid)
        responses (map #(eval-handler {:body (clojure.string/replace (:message %) #"\\n" " ")}) clojure-reqs)]
    (dorun (map #(client/get (str "https://api.hipchat.com/v1/rooms/message?from=" room-nickname
                                  "&format=json&color=green&message=" (str "%3Ccode%3E" % "%3C%2Fcode%3E")
                                  "&auth_token=" auth-token "&room_id=" (first rooms))) responses))
    (let [ttw (max 3000 (long (/ (* reset 1000) (- remaining (count responses)))))]
      (println "remaining: " remaining ", reset in " reset ", sleeping " ttw " ms...")
      (java.lang.Thread/sleep ttw)
      (future (loop-listen auth-token rooms room-nickname new-last-timestamp))
      nil)))

(defn -main
  []
  (let [{:keys [type auth-token username password rooms room-nickname]} (safe-read (slurp (io/resource "config.clj")))]
    (if (= type :xmpp)
      (let [conn (connect username password "bot")]
        (doseq [room rooms]
          (join conn room room-nickname eval-handler))
        @(promise))
      (loop-listen auth-token rooms room-nickname (time/now)))))
