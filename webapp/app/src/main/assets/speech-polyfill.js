/*
 * Android WebView에는 동작하는 Web Speech API가 없다.
 * window.SpeechRecognition / webkitSpeechRecognition 을 Android 네이티브
 * SpeechRecognizer(AndroidSpeech 브리지) 위에 다시 구현해, 웹 앱 코드를
 * 한 줄도 고치지 않고 그대로 동작하게 한다.
 *
 * continuous=true 는 Chrome과 같은 방식으로 흉내낸다: 네이티브 쪽이 세션을
 * 내부적으로 계속 재시작하고, 문장(세그먼트)마다 results[] 에 새 항목이 붙는다.
 * 세그먼트가 끝날 때까지는 isFinal=false 인 중간 결과가 같은 인덱스를 갱신한다.
 */
(function () {
  if (window.__androidSR || !window.AndroidSpeech) return;
  var bridge = window.AndroidSpeech;
  var seq = 0;
  var sessions = {};

  function makeList(arr) {
    var list = arr.slice();
    list.item = function (i) { return this[i]; };
    return list;
  }
  function makeResult(text, isFinal) {
    var r = [{ transcript: text, confidence: 0.9 }];
    r.isFinal = isFinal;
    r.item = function (i) { return this[i]; };
    return r;
  }

  function SR() {
    this.continuous = false;
    this.interimResults = false;
    this.lang = "";
    this.maxAlternatives = 1;
    this.onstart = null;
    this.onaudiostart = null;
    this.onresult = null;
    this.onerror = null;
    this.onend = null;
    this._id = 0;
    this._results = [];
    this._seg = 0;
    this._listeners = {};
  }
  SR.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };
  SR.prototype.removeEventListener = function (type, fn) {
    var l = this._listeners[type];
    if (!l) return;
    var i = l.indexOf(fn);
    if (i >= 0) l.splice(i, 1);
  };
  SR.prototype._fire = function (type, ev) {
    ev = ev || {};
    ev.type = type;
    ev.target = this;
    ev.currentTarget = this;
    var self = this;
    var handlers = [];
    if (typeof this["on" + type] === "function") handlers.push(this["on" + type]);
    (this._listeners[type] || []).forEach(function (f) { handlers.push(f); });
    handlers.forEach(function (h) {
      try { h.call(self, ev); } catch (e) { setTimeout(function () { throw e; }); }
    });
  };
  SR.prototype.start = function () {
    if (this._id && sessions[this._id]) return; // 이미 동작 중
    var id = ++seq;
    this._id = id;
    this._results = [];
    this._seg = 0;
    sessions[id] = this;
    var lang = String(this.lang || document.documentElement.lang || navigator.language || "ko-KR");
    bridge.start(id, lang, !!this.continuous, !!this.interimResults);
  };
  SR.prototype.stop = function () {
    if (this._id && sessions[this._id]) bridge.stop(this._id);
  };
  SR.prototype.abort = function () {
    if (this._id && sessions[this._id]) bridge.abort(this._id);
  };

  window.__androidSR = {
    dispatch: function (id, type, data) {
      var s = sessions[id];
      if (!s) return;
      data = data || {};
      if (type === "start") {
        s._fire("start");
        s._fire("audiostart");
      } else if (type === "result") {
        var k = s._seg;
        s._results[k] = makeResult(String(data.transcript || ""), !!data.isFinal);
        s._fire("result", { resultIndex: k, results: makeList(s._results) });
        if (data.isFinal) s._seg = k + 1;
      } else if (type === "error") {
        s._fire("error", { error: data.error || "aborted", message: data.message || "" });
      } else if (type === "end") {
        delete sessions[id];
        s._id = 0;
        s._fire("end");
      }
    },
  };

  function define(name) {
    try {
      Object.defineProperty(window, name, { value: SR, writable: true, configurable: true });
    } catch (e) {
      window[name] = SR;
    }
  }
  define("SpeechRecognition");
  define("webkitSpeechRecognition");
})();
